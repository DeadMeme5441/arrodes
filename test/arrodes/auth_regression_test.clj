(ns arrodes.auth-regression-test
  (:require [arrodes.auth :as auth]
            [arrodes.session-test :as fixtures]
            [clojure.test :refer [deftest is testing]])
  (:import [com.sun.net.httpserver HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- with-auth-store [f]
  (let [directory (fixtures/temp-directory)
        store (auth/open! (str directory "/home"))]
    (try
      (f store)
      (finally
        (auth/close! store)
        (fixtures/remove-directory! directory)))))

(defn- expired-credential [access refresh]
  {:type :oauth
   :access-token access
   :refresh-token refresh
   :expires-at 0
   :source :stored-oauth})

(deftest anthropic-subscription-oauth-is-refused-without-network-or-storage
  (with-auth-store
    (fn [store]
      (let [legacy (expired-credential "sk-ant-oat01-legacy" "legacy-refresh")
            requests (atom 0)]
        (auth/put-credential! store :anthropic legacy)
        (let [before (slurp (str (:file store)))]
          (with-redefs [auth/request! (fn [_] (swap! requests inc))]
            (doseq [options [{:type :oauth}
                             {:oauth true}
                             {:code "legacy-browser-code"}
                             {:type :api-key :api-key "sk-ant-oat01-pasted"}]]
              (let [error (try
                            (auth/login! store :anthropic options)
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
                (is (= "unsupported-oauth" (:error/code (ex-data error))))))
            (doseq [attempt [(fn [] (auth/refresh! store :anthropic {}))
                             (fn [] (auth/ensure-fresh! store :anthropic {}))]]
              (let [error (try
                            (attempt)
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
                (is (= "unsupported-oauth" (:error/code (ex-data error)))))))
          (is (zero? @requests))
          (is (= before (slurp (str (:file store)))))
          (is (= legacy (auth/credential store :anthropic))))))))

(deftest anthropic-console-api-key-is-stored-without-format-guessing
  (with-auth-store
    (fn [store]
      (let [secret "console-key-without-a-speculative-prefix"]
        (is (= :api-key
               (:type (auth/login! store :anthropic
                                   {:type :api-key :api-key secret}))))
        (is (= {:type :api-key :secret secret :source :stored-api-key}
               (auth/credential store :anthropic)))
        (is (= :configured
               (:status (auth/refresh! store :anthropic {}))))))))

(deftest stale-refresh-cannot-resurrect-logout-or-overwrite-login
  (testing "logout wins over an in-flight refresh"
    (with-auth-store
      (fn [store]
        (let [started (promise)
              release (promise)]
          (auth/put-credential! store :codex-backend
                                (expired-credential "old-access" "old-refresh"))
          (with-redefs [auth/request! (fn [_]
                                        (deliver started true)
                                        @release
                                        {:access_token "stale-access"
                                         :refresh_token "stale-refresh"
                                         :expires_in 3600})]
            (let [refresh (future (auth/refresh! store :codex-backend {}))]
              (is (= true (deref started 1000 ::timeout)))
              (auth/delete-credential! store :codex-backend)
              (deliver release true)
              (is (= :superseded
                     (:status (deref refresh 1000 {:status ::timeout}))))
              (is (nil? (auth/credential store :codex-backend)))))))))
  (testing "a newer login wins over an in-flight refresh"
    (with-auth-store
      (fn [store]
        (let [started (promise)
              release (promise)
              replacement (expired-credential "new-login" "new-login-refresh")]
          (auth/put-credential! store :codex-backend
                                (expired-credential "old-access" "old-refresh"))
          (with-redefs [auth/request! (fn [_]
                                        (deliver started true)
                                        @release
                                        {:access_token "stale-access"
                                         :refresh_token "stale-refresh"
                                         :expires_in 3600})]
            (let [refresh (future (auth/refresh! store :codex-backend {}))]
              (is (= true (deref started 1000 ::timeout)))
              (auth/put-credential! store :codex-backend replacement)
              (deliver release true)
              (is (= :superseded
                     (:status (deref refresh 1000 {:status ::timeout}))))
              (is (= replacement (auth/credential store :codex-backend))))))))))

(deftest concurrent-refreshes-exchange-one-credential-version-once
  (with-auth-store
    (fn [store]
      (let [snapshot-var (ns-resolve 'arrodes.auth 'credential-snapshot)
            refresh-lock-var (ns-resolve 'arrodes.auth 'refresh-lock)
            snapshot @snapshot-var
            lock ((deref refresh-lock-var) store :codex-backend)
            initial-snapshots (CountDownLatch. 2)
            snapshot-count (atom 0)
            requests (atom 0)]
        (auth/put-credential! store :codex-backend
                              (expired-credential "old-access" "one-refresh"))
        (with-redefs-fn
          {snapshot-var
           (fn [target provider-id]
             (let [result (snapshot target provider-id)]
               (when (<= (swap! snapshot-count inc) 2)
                 (.countDown initial-snapshots))
               result))
           #'auth/request!
           (fn [_]
             (swap! requests inc)
             {:access_token "fresh-access"
              :refresh_token "fresh-refresh"
              :expires_in 3600})}
          #(let [[first-refresh second-refresh]
                 (locking lock
                   (let [first-refresh (future
                                         (auth/refresh! store :codex-backend {}))
                         second-refresh (future
                                          (auth/refresh! store :codex-backend {}))]
                     (is (.await initial-snapshots 1 TimeUnit/SECONDS))
                     [first-refresh second-refresh]))
                 results [(deref first-refresh 1000 {:status ::timeout})
                          (deref second-refresh 1000 {:status ::timeout})]]
             (is (= #{:refreshed :superseded}
                    (set (map :status results))))
             (is (= 1 @requests))))))))

(deftest refresh-retains-an-unrotated-refresh-token
  (doseq [provider [:codex-backend]]
    (testing (name provider)
      (with-auth-store
        (fn [store]
          (let [requests (atom 0)]
            (auth/put-credential! store provider
                                  (expired-credential "old-access" "keep-refresh"))
            (with-redefs [auth/request! (fn [_]
                                          (swap! requests inc)
                                          {:access_token (str "access-" @requests)
                                           :expires_in 0})]
              (is (= :refreshed (:status (auth/refresh! store provider {}))))
              (is (= "keep-refresh"
                     (:refresh-token (auth/credential store provider))))
              (is (= :refreshed (:status (auth/refresh! store provider {})))))
            (is (= 2 @requests))
            (is (= "keep-refresh"
                   (:refresh-token (auth/credential store provider))))))))))

(defn- unused-port []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        port (-> server .getAddress .getPort)]
    (.start server)
    (.stop server 0)
    port))

(deftest notification-failure-closes-browser-callback-servers
  (let [callback-var (ns-resolve 'arrodes.auth 'callback-server)
        original @callback-var]
    (doseq [[provider options]
            [[:codex-backend {:type :oauth :flow :browser}]]]
      (testing (name provider)
        (with-auth-store
          (fn [store]
            (let [port (unused-port)
                  callback (atom nil)]
              (try
                (with-redefs-fn
                  {callback-var
                   (fn [host _ path expected-state]
                     (let [value (original host port path expected-state)]
                       (reset! callback value)
                       value))}
                  #(is (thrown-with-msg?
                        clojure.lang.ExceptionInfo
                        #"notification failed"
                        (auth/login! store provider
                                     (assoc options :callback-host "127.0.0.1"
                                            :on-event
                                            (fn [_]
                                              (throw (ex-info "notification failed" {}))))))))
                (is (some? @callback))
                (let [rebound (HttpServer/create
                               (InetSocketAddress. "127.0.0.1" port) 0)]
                  (try
                    (.start rebound)
                    (finally
                      (.stop rebound 0))))
                (finally
                  (when-let [server (some-> @callback :server)]
                    (try
                      (.stop ^HttpServer server 0)
                      (catch Exception _))))))))))))
