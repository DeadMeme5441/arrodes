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

(defn- auth-query [event]
  ((deref (ns-resolve 'arrodes.auth 'query-map))
   (.getRawQuery (java.net.URI/create (:url event)))))

(deftest anthropic-browser-login-uses-pkce-state-and-private-storage
  (doseq [provider [:anthropic :claude-alias]]
    (with-auth-store
      (fn [store]
        (let [event (atom nil) captured (atom nil)
              before (System/currentTimeMillis)]
          (with-redefs [auth/request! (fn [request]
                                       (reset! captured request)
                                       {:access_token "oauth-access"
                                        :refresh_token "oauth-refresh" :expires_in 3600})]
            (is (= :logged-in
                   (:status (auth/login!
                             store provider
                             {:type :oauth :anthropic-profile? true
                              :on-event #(reset! event %)
                              :input (fn [_] (str "code#" (get (auth-query @event) "state")))})))))
          (let [query (auth-query @event)
                body (:body @captured)
                c (auth/credential store provider)
                digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                (.getBytes (:code_verifier body) java.nio.charset.StandardCharsets/US_ASCII))]
            (is (= "https://claude.ai/oauth/authorize"
                   (first (clojure.string/split (:url @event) #"[?]"))))
            (is (= "S256" (get query "code_challenge_method")))
            (is (= (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) digest)
                   (get query "code_challenge")))
            (is (= (get query "state") (:state body)))
            (is (= (get query "redirect_uri") (:redirect_uri body)))
            (is (= "9d1c250a-e61b-44d9-88ed-5944d1962f5e" (:client_id body)))
            (is (= "https://api.anthropic.com/v1/oauth/token" (:url @captured)))
            (is (= "authorization_code" (:grant_type body)))
            (is (= "code" (:code body)))
            (is (nil? (:form @captured)))
            (is (= :anthropic (:oauth-provider c)))
            (is (= "oauth-refresh" (:refresh-token c)))
            (is (<= (+ before 3300000) (:expires-at c) (+ (System/currentTimeMillis) 3300000)))
            (is (not (clojure.string/includes? (pr-str (auth/credential-info store)) "oauth-access")))))))))

(deftest anthropic-invalid-state-and-cancellation-never-write-credentials
  (doseq [code ["code#wrong" "code" ""]]
    (with-auth-store
      (fn [store]
        (with-redefs [auth/request! (fn [_] (throw (AssertionError. "Unexpected exchange")))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (auth/login! store :anthropic {:type :oauth :code code}))))
        (is (nil? (auth/credential store :anthropic))))))
  (with-auth-store
    (fn [store]
      (let [event (atom nil) cancelled (atom false)]
        (with-redefs [auth/request! (fn [_]
                                     (reset! cancelled true)
                                     {:access_token "do-not-store" :expires_in 3600})]
          (is (thrown? clojure.lang.ExceptionInfo
                       (auth/login! store :anthropic
                                    {:type :oauth :cancelled? #(deref cancelled)
                                     :on-event #(reset! event %)
                                     :input (fn [_] (str "code#" (get (auth-query @event) "state")))}))))
        (is (nil? (auth/credential store :anthropic)))))))

(deftest anthropic-oauth-refresh-rotates-and-retains-owner
  (with-auth-store
    (fn [store]
      (let [captured (atom nil)]
        (auth/put-credential! store :claude-alias
                              (assoc (expired-credential "old" "refresh") :oauth-provider :anthropic))
        (with-redefs [auth/request! (fn [request]
                                     (reset! captured request)
                                     {:access_token "new" :refresh_token "rotated" :expires_in 3600})]
          (is (= "new" (:access-token (auth/ensure-fresh! store :claude-alias {})))))
        (is (= "https://api.anthropic.com/v1/oauth/token" (:url @captured)))
        (is (= "refresh_token" (get-in @captured [:body :grant_type])))
        (is (= "refresh" (get-in @captured [:body :refresh_token])))
        (is (= "rotated" (:refresh-token (auth/credential store :claude-alias))))
        (is (= :anthropic (:oauth-provider (auth/credential store :claude-alias))))))))

(deftest anthropic-oauth-token-is-not-an-api-key
  (with-auth-store
    (fn [store]
      (is (thrown? clojure.lang.ExceptionInfo
                   (auth/login! store :anthropic {:type :api-key :api-key "sk-ant-oat01-pasted"})))
      (is (nil? (auth/credential store :anthropic))))))

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
  (doseq [provider [:codex-backend :anthropic]]
    (testing "logout wins over an in-flight refresh"
      (with-auth-store
        (fn [store]
          (let [started (promise)
                release (promise)]
            (auth/put-credential! store provider
                                  (expired-credential "old-access" "old-refresh"))
            (with-redefs [auth/request! (fn [_]
                                          (deliver started true)
                                          @release
                                          {:access_token "stale-access"
                                           :refresh_token "stale-refresh"
                                           :expires_in 3600})]
              (let [refresh (future (auth/refresh! store provider {}))]
                (is (= true (deref started 1000 ::timeout)))
                (auth/delete-credential! store provider)
                (deliver release true)
                (is (= :superseded
                       (:status (deref refresh 1000 {:status ::timeout}))))
                (is (nil? (auth/credential store provider)))))))))
    (testing "a newer login wins over an in-flight refresh"
      (with-auth-store
        (fn [store]
          (let [started (promise)
                release (promise)
                replacement (expired-credential "new-login" "new-login-refresh")]
            (auth/put-credential! store provider
                                  (expired-credential "old-access" "old-refresh"))
            (with-redefs [auth/request! (fn [_]
                                          (deliver started true)
                                          @release
                                          {:access_token "stale-access"
                                           :refresh_token "stale-refresh"
                                           :expires_in 3600})]
              (let [refresh (future (auth/refresh! store provider {}))]
                (is (= true (deref started 1000 ::timeout)))
                (auth/put-credential! store provider replacement)
                (deliver release true)
                (is (= :superseded
                       (:status (deref refresh 1000 {:status ::timeout}))))
                (is (= replacement (auth/credential store provider)))))))))))

(deftest concurrent-refreshes-exchange-one-credential-version-once
  (doseq [provider [:codex-backend :anthropic]]
    (with-auth-store
      (fn [store]
        (let [snapshot-var (ns-resolve 'arrodes.auth 'credential-snapshot)
              refresh-lock-var (ns-resolve 'arrodes.auth 'refresh-lock)
              snapshot @snapshot-var
              lock ((deref refresh-lock-var) store provider)
              initial-snapshots (CountDownLatch. 2)
              snapshot-count (atom 0)
              requests (atom 0)]
          (auth/put-credential! store provider
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
                                           (auth/refresh! store provider {}))
                           second-refresh (future
                                            (auth/refresh! store provider {}))]
                       (is (.await initial-snapshots 1 TimeUnit/SECONDS))
                       [first-refresh second-refresh]))
                   results [(deref first-refresh 1000 {:status ::timeout})
                            (deref second-refresh 1000 {:status ::timeout})]]
               (is (= #{:refreshed :superseded}
                      (set (map :status results))))
               (is (= 1 @requests)))))))))

(deftest refresh-retains-an-unrotated-refresh-token
  (doseq [provider [:codex-backend :anthropic]]
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
            [[:codex-backend {:type :oauth :flow :browser}]
             [:anthropic {:type :oauth :flow :browser}]]]
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

(deftest anthropic-loopback-callback-validates-state-and-closes
  (with-auth-store
    (fn [store]
      (let [callback-var (ns-resolve 'arrodes.auth 'callback-server)
            original @callback-var
            port (unused-port)
            client (java.net.http.HttpClient/newHttpClient)
            call (fn [query]
                   (.statusCode
                    (.send client
                           (-> (java.net.http.HttpRequest/newBuilder
                                (java.net.URI/create (str "http://127.0.0.1:" port "/callback?" query)))
                               .GET .build)
                           (java.net.http.HttpResponse$BodyHandlers/ofString))))]
        (with-redefs-fn
          {callback-var (fn [host _ path state] (original host port path state))
           #'auth/request! (fn [request]
                             (is (= "callback-code" (get-in request [:body :code])))
                             {:access_token "callback-access" :refresh_token "refresh" :expires_in 3600})}
          #(is (= :logged-in
                  (:status (auth/login! store :anthropic
                                        {:type :oauth :timeout-ms 2000
                                         :on-event (fn [event]
                                                     (is (= 400 (call "code=wrong&state=wrong")))
                                                     (is (= 200 (call (str "code=callback-code&state="
                                                                           (get (auth-query event) "state"))))))})))))
        (is (= "callback-access" (:access-token (auth/credential store :anthropic))))
        (let [rebound (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
          (try (.start rebound) (finally (.stop rebound 0))))))))

(deftest anthropic-failed-refresh-keeps-the-previous-credential
  (doseq [response [{} {:access_token " "} {:access_token "new" :expires_in "invalid"}]]
    (with-auth-store
      (fn [store]
        (let [old (expired-credential "old" "refresh")]
          (auth/put-credential! store :anthropic old)
          (with-redefs [auth/request! (constantly response)]
            (is (thrown? clojure.lang.ExceptionInfo (auth/refresh! store :anthropic {}))))
          (is (= old (auth/credential store :anthropic))))))))
