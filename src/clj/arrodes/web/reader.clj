(ns arrodes.web.reader
  "Bounded, inert HTTP page retrieval. No browser, cache, or remote reader service."
  (:require [arrodes.web.data :as web]
            [clojure.string :as str])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandler
            HttpResponse$BodySubscriber]
           [java.nio ByteBuffer]
           [java.nio.charset Charset StandardCharsets]
           [java.time Duration]
           [java.util.concurrent CompletableFuture ExecutionException TimeUnit TimeoutException
            Flow$Subscription]
           [java.util.zip GZIPInputStream]
           [org.jsoup Jsoup]
           [org.jsoup.nodes Document Element Node TextNode]
           [org.jsoup.select NodeTraversor NodeVisitor]))

(def ^:private byte-limit (* 2 1024 1024))
(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 15))
             (.followRedirects HttpClient$Redirect/NEVER)
             (.build))))

(defn- fail! [code message data]
  (throw (ex-info message (assoc data :error/code (str "web/" (name code))
                                :error/type (keyword "web" (name code))
                                :retryable? false))))

(defn- url! [url]
  (when-not (web/http-url? url)
    (fail! :invalid-url "Web reads require an HTTP(S) URL without embedded credentials" {:url url}))
  (URI. ^String url))

(defn- active! [deadline options]
  (when (or (.isInterrupted (Thread/currentThread))
            (when-let [f (:cancelled? options)] (f)))
    (fail! :cancelled "Web read cancelled" {}))
  (when (>= (System/nanoTime) deadline)
    (fail! :timeout "Web read exceeded its total timeout" {})))

(defn- bounded-subscriber [subscription stopped?]
  (let [result (CompletableFuture.)
        output (ByteArrayOutputStream.)
        scratch (byte-array 8192)]
    (reify HttpResponse$BodySubscriber
      (getBody [_] result)
      (onSubscribe [_ s]
        (reset! subscription s)
        (if @stopped? (.cancel ^Flow$Subscription s) (.request ^Flow$Subscription s 1)))
      (onNext [_ buffers]
        (try
          (doseq [^ByteBuffer buffer buffers]
            (let [n (.remaining buffer)]
              (when (> (+ (.size output) n) byte-limit)
                (fail! :body-too-large "Web response exceeds the 2 MiB compressed body limit"
                       {:max-bytes byte-limit :stage :compressed}))
              (loop []
                (when (.hasRemaining buffer)
                  (let [chunk (min (.remaining buffer) (alength scratch))]
                    (.get buffer scratch 0 chunk)
                    (.write output scratch 0 chunk)
                    (recur))))))
          (.request ^Flow$Subscription @subscription 1)
          (catch Throwable e
            (.cancel ^Flow$Subscription @subscription)
            (.completeExceptionally result e))))
      (onError [_ error] (.completeExceptionally result error))
      (onComplete [_] (.complete result (.toByteArray output))))))

(defn- request! [^URI uri deadline options]
  (active! deadline options)
  (let [subscription (atom nil)
        stopped? (atom false)
        remaining (max 1 (long (/ (- deadline (System/nanoTime)) 1000000)))
        request (-> (HttpRequest/newBuilder uri)
                    (.timeout (Duration/ofMillis remaining))
                    (.header "Accept" "text/html, text/plain, text/markdown, application/json, application/xhtml+xml")
                    (.header "Accept-Encoding" "gzip")
                    (.header "User-Agent" "Arrodes-Web-Reader")
                    (.GET) (.build))
        pending (.sendAsync ^HttpClient @http-client request
                            (reify HttpResponse$BodyHandler
                              (apply [_ response-info] (bounded-subscriber subscription stopped?))))]
    (try
      (loop []
        (active! deadline options)
        (let [response (try (.get pending 25 TimeUnit/MILLISECONDS)
                            (catch TimeoutException _ nil))]
          (if response response (recur))))
      (catch ExecutionException e
        (let [cause (.getCause e)]
          (if (instance? clojure.lang.ExceptionInfo cause)
            (throw cause)
            (fail! (if (instance? java.net.http.HttpTimeoutException cause) :timeout :http)
                   (str "Web HTTP request failed: " (ex-message cause)) {:url (str uri)}))))
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        (fail! :cancelled "Web read cancelled" {}))
      (finally
        (reset! stopped? true)
        (when-let [s @subscription] (.cancel ^Flow$Subscription s))
        (.cancel pending true)))))

(defn- header [response name]
  (-> response .headers (.firstValue name) (.orElse nil)))

(defn- fetch! [uri deadline options]
  (loop [uri uri seen #{} redirects 0]
    (active! deadline options)
    (when (contains? seen (str (.normalize ^URI uri)))
      (fail! :redirect-loop "Web redirect loop" {:url (str uri)}))
    (let [response (request! uri deadline options)
          status (.statusCode response)]
      (cond
        (contains? #{301 302 303 307 308} status)
        (do
          (when (>= redirects 5)
            (fail! :too-many-redirects "Web read exceeded five redirects" {:url (str uri)}))
          (let [location (header response "Location")]
            (when (str/blank? location)
              (fail! :redirect "Web redirect omitted Location" {:url (str uri) :status status}))
            (let [next-url (try (str (.resolve ^URI uri location))
                                (catch Exception _
                                  (fail! :redirect "Invalid web redirect Location" {:url (str uri)})))]
              (recur (url! next-url) (conj seen (str (.normalize ^URI uri))) (inc redirects)))))

        (<= 200 status 299) {:response response :uri uri}
        :else (fail! :http "Web server returned an unsuccessful HTTP status"
                     {:url (str uri) :status status})))))

(defn- decoded-bytes [response deadline options]
  (let [bytes (.body response)
        encoding (some-> (header response "Content-Encoding") str/trim str/lower-case)]
    (cond
      (or (str/blank? encoding) (= "identity" encoding)) bytes
      (= "gzip" encoding)
      (with-open [input (GZIPInputStream. (ByteArrayInputStream. bytes))
                  output (ByteArrayOutputStream.)]
        (let [buffer (byte-array 8192)]
          (loop []
            (active! deadline options)
            (let [n (.read input buffer)]
              (when (pos? n)
                (when (> (+ (.size output) n) byte-limit)
                  (fail! :body-too-large "Web response exceeds the 2 MiB decoded body limit"
                         {:max-bytes byte-limit :stage :decoded}))
                (.write output buffer 0 n)
                (recur)))))
        (.toByteArray output))
      :else (fail! :unsupported-encoding "Unsupported web response content encoding"
                   {:encoding encoding}))))

(defn- charset-name [content-type]
  (some-> (re-find #"(?i)(?:^|;)\s*charset\s*=\s*[\"']?([^;\s\"']+)" content-type) second))

(defn- charset! [name]
  (try (Charset/forName name)
       (catch Exception _ (fail! :unsupported-charset "Unsupported web response charset" {:charset name}))))

(defn- decode-text [bytes charset]
  (let [text (String. ^bytes bytes ^Charset charset)]
    (if (str/starts-with? text "\uFEFF") (subs text 1) text)))

(defn- challenge! [^Document document]
  (when (or (re-find #"(?i)^(?:just a moment|attention required|access denied|verify (?:that )?you are human|captcha|security verification)[.!…\s]*(?:[-|:].*)?$"
                    (.title document))
            (.selectFirst document "#challenge-form, #cf-challenge-running, #challenge-running")
            (re-find #"(?i)^(?:verify (?:that )?you are human|checking your browser|complete the captcha)(?:[.!…\s]|$)"
                     (.text (.body document))))
    (fail! :blocked "Web page is an access challenge, not readable source content" {})))

(defn- html-content [^Document document deadline options max-characters]
  (.remove (.select document
                    "script, style, noscript, template, nav, footer, aside, body > header, [role=navigation], [role=banner], [role=contentinfo], [hidden], [aria-hidden=true]"))
  (let [root (or (.selectFirst document "main, article, [role=main]") (.body document))
        output (StringBuilder.)
        total (volatile! 0)
        trailing (volatile! 0)
        trailing-breaks (volatile! 0)
        pre-depth (volatile! 0)
        pending (volatile! "")
        emit! (fn [^String text]
                (if (and (zero? @pre-depth) (str/blank? text))
                  ;; Formatting whitespace and empty containers must not consume
                  ;; the retained prefix ahead of actual source content.
                  (when (and (pos? @total) (pos? (count text)))
                    (vreset! pending
                             (cond
                               (str/includes? text "\n\n") "\n\n"
                               (= @pending "\n\n") @pending
                               (str/includes? text "\n") "\n"
                               (seq @pending) @pending
                               :else " ")))
                  (let [separator (if (or (pos? @pre-depth)
                                          (str/starts-with? text "\n"))
                                    ""
                                    (if (= @pending " ")
                                      (if (pos? @trailing) "" " ")
                                      (subs @pending 0 (max 0 (- (count @pending) @trailing-breaks)))))
                        text (if (seq separator) (str separator (str/triml text)) text)
                        text (if (zero? @total) (str/triml text) text)
                        n (.length text)
                        spaces (loop [i n]
                                 (if (and (pos? i) (<= (int (.charAt text (dec i))) 32))
                                   (recur (dec i)) (- n i)))
                        breaks (loop [i (- n spaces) count 0]
                                 (if (< i n)
                                   (recur (inc i) (if (= \newline (.charAt text i)) (inc count) count))
                                   count))]
                    (vreset! pending "")
                    (vswap! total + n)
                    (if (= spaces n) (vswap! trailing + spaces) (vreset! trailing spaces))
                    (if (= spaces n)
                      (vswap! trailing-breaks + breaks)
                      (vreset! trailing-breaks breaks))
                    (let [retained (min n (- max-characters (.length output)))]
                      (when (pos? retained) (.append output text 0 (int retained)))))))
        block-tags #{"p" "div" "section" "article" "main" "blockquote" "ul" "ol" "li"
                     "table" "tr" "dl" "dt" "dd" "h1" "h2" "h3" "h4" "h5" "h6"}]
    (when (str/blank? (.text ^Element root))
      (fail! :empty-content "Web response contains no readable content" {}))
    (NodeTraversor/traverse
     (reify NodeVisitor
       (head [_ node depth]
         (active! deadline options)
         (cond
           (instance? TextNode node)
           (emit! (if (pos? @pre-depth) (.getWholeText ^TextNode node)
                      (str/replace (.getWholeText ^TextNode node) #"[\s\u00a0]+" " ")))
           (instance? Element node)
           (let [element ^Element node tag (.normalName element)]
             (when (and (zero? @pre-depth) (contains? block-tags tag)) (emit! "\n\n"))
             (cond
               (= "pre" tag) (do (emit! "\n\n```\n") (vswap! pre-depth inc))
               (= "br" tag) (emit! "\n")
               (and (= "code" tag) (zero? @pre-depth)) (emit! "`")
               (re-matches #"h[1-6]" tag) (emit! (str (apply str (repeat (Integer/parseInt (subs tag 1)) "#")) " "))
               (= "li" tag) (emit! "- ")
               (= "a" tag) (when (web/http-url? (.absUrl element "href")) (emit! "["))))))
       (tail [_ node depth]
         (when (instance? Element node)
           (let [element ^Element node tag (.normalName element)]
             (cond
               (= "pre" tag) (do (vswap! pre-depth dec) (emit! "\n```\n\n"))
               (and (= "code" tag) (zero? @pre-depth)) (emit! "`")
               (= "a" tag) (let [url (.absUrl element "href")]
                             (when (web/http-url? url) (emit! (str "](" url ")")))))
             (when (and (zero? @pre-depth) (contains? block-tags tag)) (emit! "\n\n"))))))
     ^Node root)
    (let [length (- @total @trailing)]
      {:content (subs (str output) 0 (min max-characters length))
       :truncated? (> length max-characters)})))

(defn read!
  "Fetch one textual HTTP(S) page with a total deadline and bounded bytes/retained text.
  Page prose is untrusted data. :raw? skips HTML content extraction, not safety bounds."
  [{:keys [url raw? timeout-ms max-characters]
    :or {timeout-ms 30000 max-characters 200000}} options]
  (when-not (and (integer? timeout-ms) (<= 1 timeout-ms 120000)
                 (integer? max-characters) (<= 1 max-characters 1000000)
                 (or (nil? raw?) (boolean? raw?)))
    (fail! :invalid-arguments "Invalid web reader timeout, character bound, or raw flag" {}))
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))
        requested (url! url)
        {:keys [response uri]} (fetch! requested deadline options)
        content-type (or (header response "Content-Type") "")
        media-type (-> content-type (str/split #";" 2) first str/trim str/lower-case)
        html? (contains? #{"text/html" "application/xhtml+xml"} media-type)]
    (when-not (or (str/starts-with? media-type "text/") html?
                  (= "application/json" media-type) (str/ends-with? media-type "+json"))
      (fail! :unsupported-content-type "Web reader only supports textual responses"
             {:url (str uri) :content-type content-type}))
    (let [bytes (decoded-bytes response deadline options)
          charset (charset-name content-type)
          _ (when charset (charset! charset))
          document (when html?
                     (with-open [input (ByteArrayInputStream. bytes)]
                       (Jsoup/parse input charset (str uri))))
          _ (when document (challenge! document))
          extracted (when (and html? (not raw?))
                      (html-content document deadline options max-characters))
          content (if extracted (:content extracted)
                      (decode-text bytes (if document (.charset ^Document document)
                                             (if charset (charset! charset) StandardCharsets/UTF_8))))
          _ (active! deadline options)
          _ (when (str/blank? content)
              (fail! :empty-content "Web response contains no readable content" {:url (str uri)}))
          truncated? (if extracted (:truncated? extracted) (> (count content) max-characters))
          title (when document (.title ^Document document))]
      (web/validate!
       ::web/page
       (cond-> {::web/backend :http ::web/url url ::web/final-url (str uri)
                ::web/content (if (and truncated? (not extracted)) (subs content 0 max-characters) content)
                ::web/content-type content-type ::web/truncated? truncated?
                ::web/fetched-at (System/currentTimeMillis)}
         (not (str/blank? title)) (assoc ::web/title title)
         truncated? (assoc ::web/notes [(str "Retained the first " max-characters
                                           " characters; remaining fetched text was omitted.")]))))))
