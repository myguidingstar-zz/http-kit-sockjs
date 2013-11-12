(ns methojure.sockjs.core
  (:use [compojure.core :only [GET POST DELETE OPTIONS ANY context routes]]
        methojure.sockjs.action
        methojure.sockjs.filter))

(defn ->f [options req & fs]
  (reduce #(%2 req options %1) {} fs))

(defn sockjs-handler [path handler & [options]]
  (let [options (merge {:prefix path
                        :response-limit (* 128 1024)
                        :origin ["*:*"]
                        :websocket true
                        :jsessionid false
                        :heatbeat-delay 25000
                        :disconnect-delay 5000
                        :sockjs-handler handler
                        :sockjs-url "http://cdn.sockjs.org/sockjs-0.3.min.js"}
                       (or options {}))]
    (context
     (:prefix options) []
     (GET "/" req (->f options req
                       welcome-screen))
     (GET "/iframe.html" req (->f options req
                                  iframe cache-for expose))
     (GET "/iframe:key.html" req (->f options req
                                      iframe cache-for expose))
     (OPTIONS "/info" req (->f options req
                               h-sid xhr-cors cache-for info-options expose))
     (GET "/info" req (->f options req xhr-cors h-no-cache info expose))
     (context
      ["/:server/:session" :server #"[^/.]+" :session #"[^/.]+"]
      [server session]
      (GET "/jsonp" req (jsonp req session options))
      (POST "/jsonp_send" req (jsonp-send req session options))
      (OPTIONS "/xhr_send" req (->f options req
                                    h-sid xhr-cors cache-for expose))
      (POST "/xhr_send" req (xhr-send req session options))
      (OPTIONS "/xhr" req (->f options req h-sid xhr-cors cache-for expose))
      (POST "/xhr" req (xhr-polling req session options))
      (OPTIONS "/xhr_streaming" req (->f options req
                                         h-sid xhr-cors cache-for expose))
      (POST "/xhr_streaming" req (xhr-streaming req session options))
      (GET "/eventsource" req (eventsource req session options))
      (GET "/htmlfile" req (htmlfile req session options))
      (GET "/websocket" req (websocket req session options))
      (POST "/websocket" req {:status 405
                              :headers {"Allow" "true"}})))))


