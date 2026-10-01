(ns arrodes.web.data
  "Native web research result contracts; provider prose stays distinct from sources."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [arrodes.value :as value]))

(defn http-url? [url]
  (and (string? url)
       (try
         (let [uri (java.net.URI. url)]
           (and (contains? #{"http" "https"} (some-> (.getScheme uri) str/lower-case))
                (not (str/blank? (.getHost uri)))
                (nil? (.getUserInfo uri))))
         (catch java.net.URISyntaxException _ false))))

(s/def ::query (s/and string? #(not (str/blank? %))))
(s/def ::provider keyword?)
(s/def ::backend #{:hosted :mcp :http})
(s/def ::model string?)
(s/def ::url http-url?)
(s/def ::final-url (s/nilable ::url))
(s/def ::title string?)
(s/def ::snippet string?)
(s/def ::date string?)
(s/def ::cited-text string?)
(s/def ::source (s/keys :req [::url] :opt [::title ::snippet ::date]))
(s/def ::citation (s/keys :req [::url] :opt [::title ::cited-text]))
(s/def ::sources (s/coll-of ::source :kind vector?))
(s/def ::citations (s/coll-of ::citation :kind vector?))
(s/def ::answer string?)
(s/def ::search-queries (s/coll-of string? :kind vector?))
(s/def ::usage map?)
(s/def ::cost map?)
(s/def ::fetched-at nat-int?)
(s/def ::content string?)
(s/def ::content-type string?)
(s/def ::truncated? boolean?)
(s/def ::notes (s/coll-of string? :kind vector?))
(s/def ::content-blocks vector?)
(s/def ::structured-content any?)
(s/def ::server string?)
(s/def ::tool string?)
(s/def ::arguments map?)
(s/def ::raw-response map?)
(s/def ::search-result
  (s/keys :req [::backend ::provider ::query ::sources ::citations ::fetched-at]
          :opt [::model ::answer ::search-queries ::usage ::cost ::content-blocks
                ::structured-content ::server ::tool ::arguments ::notes ::raw-response]))
(s/def ::page
  (s/keys :req [::backend ::url ::final-url ::content ::content-type ::truncated? ::fetched-at]
          :opt [::provider ::title ::notes ::content-blocks ::structured-content ::server ::tool ::arguments]))

(defn validate! [spec result]
  (when-not (s/valid? spec result)
    (value/fail! :invalid-web-result "Invalid native web research result"
                 {:spec spec :problems (::s/problems (s/explain-data spec result))}))
  result)
