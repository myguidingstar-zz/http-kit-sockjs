(ns methojure.sockjs.filter
  (:require [cheshire.core :as json]))

(defn- assoc-header [res & kvs]
  (update-in res [:headers]
             #(reduce (fn [r [k v]]
                        (assoc r k v))
                      %
                      (partition 2 kvs))))

(defn md5
  "Generate a md5 checksum for the given string"
  [token]
  (let [hash-bytes
         (doto (java.security.MessageDigest/getInstance "MD5")
               (.reset)
               (.update (.getBytes token)))]
       (.toString
         (new java.math.BigInteger 1 (.digest hash-bytes))
         16))) ; Use base16 i.e. hex

(defn welcome-screen [req options res]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body "Welcome to SockJS!\n"})

(def iframe-tmpl
"<!DOCTYPE html>
<html>
<head>
  <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\" />
  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />
  <script>
    document.domain = document.domain;
    _sockjs_onload = function(){SockJS.bootstrap_iframe();};
  </script>
  <script src=\"{{ sockjs-url }}\"></script>
</head>
<body>
  <h2>Don't panic!</h2>
  <p>This is a SockJS hidden iframe. It's used for cross domain magic.</p>
</body>
</html>")

(defn iframe [req options & [res]]
  (let [res (or res {})
        content (.replace iframe-tmpl
                          "{{ sockjs-url }}" (:sockjs-url options))
        quoted_md5 (md5 content)]
    (if (= ((req :headers) "if-none-match") quoted_md5)
      (-> res
          (assoc :status 304)
          (assoc-header "Content-Type" nil)
          (assoc :body nil))
      (-> res
          (assoc :body content)
          (assoc :status 200)
          (assoc-header "Content-Type" "text/html; charset=UTF-8"
                         "ETag" quoted_md5)))))

(defn cache-for [req options res]
  (let [time (* 365 24 60 60)
        exp (java.util.Date. (+ (.getTime (java.util.Date.)) (* time 1000)))]
    (assoc-header res
                   "Cache-Control" (str "public, max-age=" time)
                   "Expires" (.toGMTString exp))))

(defn h-no-cache [req options res]
  (assoc-header
   res
   "Cache-Control" "no-store, no-cache, must-revalidate, max-age=0"))

(defn expose [req options res]
  (let [res (if (contains? (:headers res) "Content-Type")
              res
              (assoc-header res "Content-Type" "text/plain"))
        res (if (contains? res :body)
              res
              (assoc res :body ""))
        res (if (contains? res :status)
              res
              (assoc res :status 200))]
    (assoc-header res
                 "Content-Length" (str (count (:body res))))))

(defn h-sid [req options res]
  (if (:jsessionid options)
    (let [id (or (:value ((req :cookies) "JSESSIONID"))
                 "dummy")]
      (-> res
          (assoc-header "Set-Cookie"
                        (str "JSESSIONID=" id "; path=/"))))
    res))

(defn xhr-cors [req options res]
  (let [headers (:headers req)
        origin (if (or (not (contains? headers "origin"))
                       (= (headers "origin") "null"))
                 "*"
                 (headers "origin"))
        acrh (headers "access-control-request-headers")
        res (if (not (nil? acrh))
              (assoc-header res "Access-Control-Allow-Headers" acrh)
              res)]
    (-> res
        (assoc :status 204)
        (assoc-header
         "Access-Control-Allow-Methods" "OPTIONS, POST"
         "Access-Control-Max-Age" (str (* 365 24 60 60))
         "Access-Control-Allow-Origin" origin
         "Access-Control-Allow-Credentials" "true"))))

(defn info-options [req options res]
  (-> res
      (assoc-header "Access-Control-Allow-Methods" "OPTIONS, GET"
                    "Access-Control-Max-Age" (str (* 365 24 60 60)))
      (assoc :status 204)))

(defn status-200 [req options res]
  (assoc res :status 200))

(defn info [req options res]
  (-> res
      (assoc-header "Content-Type" "application/json; charset=UTF-8")
      (assoc :status 200)
      (assoc :body (json/generate-string
                    {:websocket (:websocket options)
                     :origins (:origin options)
                     :cookie_needed (:jsessionid options)
                     :entropy (.nextInt (java.util.Random.))}))))

(defn xhr-options [req options res]
  (-> res
      (assoc :status 204)
      (assoc-header "Access-Control-Allow-Methods" "OPTIONS, POST"
                    "Access-Control-Max-Age" (str (* 365 24 60 60)))))
