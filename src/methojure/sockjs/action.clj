(ns methojure.sockjs.action
  (:require [cheshire.core :as json]
            [clojure.string :as cstr]
            [methojure.sockjs.filter :as f]
            [methojure.sockjs.session :as session]
            [org.httpkit.server :as server]))

(defn error-500 [msg]
  {:status 500
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body msg})

(def response-404
  {:status 404
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body ""})

(defn initialize-session
  "initializes a new session"
  [s channel sockjs-handler]
  ;; register to storage
  (session/register-session s)
  ;; register channel
  (session/register-new-channel! (:id s) channel)
  ;; call on open handler
  (session/update-session! (:id s) #(session/on-open sockjs-handler %)))

(defn websocket-on-receive
  "returns the websocket on-receive function for a given session and channel"
  [session-id sockjs-handler channel]
  (fn [msg]
    (when-not (empty? msg) ;; only handle non-empty messages
      (try
        (let [msgs (json/parse-string msg)]
          (doseq [m msgs]
            (session/update-session!
             session-id
             #(session/on-message sockjs-handler % m))))
        (catch com.fasterxml.jackson.core.JsonParseException e
          ;; close channel when invalid json is send.
          (server/close channel))))))

(defn websocket-on-close
  "returns the websocket on-close function for a given session id"
  [session-id]
  (fn [status]
    ;; when the connection is closed, close also the session
    (session/update-session! session-id #(session/close! % 3000 status))))

(defn websocket
  "the websocket request handler"
  [req session-id {:keys [sockjs-handler heatbeat-delay disconnect-delay]}]
  (if-not (:websocket? req)
    ;; if no websocket request return a message to upgrade to websocket
    {:status 400
     :body "Can \"Upgrade\" only to \"WebSocket\"."}
    ;; if we have a websocket connection initial the channel
    (server/with-channel req channel
      (if-not (session/session? session-id)
        (let [s (session/create-streaming-session
                 session-id identity
                 :sockjs-handler sockjs-handler
                 :heatbeat-delay heatbeat-delay
                 :disconnect-delay disconnect-delay)]
          (initialize-session s channel sockjs-handler))
        (session/register-new-channel! session-id channel))
      (server/on-receive
       channel
       (websocket-on-receive session-id sockjs-handler channel))
      (server/on-close
       channel
       (websocket-on-close session-id)))))

(defn xhr-streaming
  "the xhr streaming request handler."
  [req session-id {:keys [sockjs-handler heatbeat-delay disconnect-delay
                          response-limit] :as opts}]
  (server/with-channel req channel
    ;; always send 2049 bytes preclude
    (server/send! channel
                  (->> {:status 200
                        :headers {"Content-Type"
                                  "application/javascript; charset=UTF-8"}
                        :body (str (apply str (repeat 2048 "h")) "\n")}
                       (f/h-sid req opts)
                       (f/h-no-cache req opts)
                       (f/xhr-cors req opts)
                       (f/status-200 req opts))
                  false)
    ;; register the new channel
    (if-not (session/session? session-id)
      (let [s (session/create-streaming-session
               session-id (fn [m] (str m "\n"))
               :response-limit response-limit
               :heatbeat-delay heatbeat-delay
               :disconnect-delay disconnect-delay
               :sockjs-handler sockjs-handler)]
        (initialize-session s channel sockjs-handler))
      (session/register-new-channel! session-id channel))))

(defn eventsource
  "the eventsource handler"
  [req session-id {:keys [sockjs-handler heatbeat-delay disconnect-delay
                          response-limit] :as opts}]
  (server/with-channel req channel
    (if-not (session/session? session-id)
      (let [s (session/create-streaming-session
               session-id (fn [m] (str "data: " m "\r\n\r\n"))
               :response-limit response-limit
               :heatbeat-delay heatbeat-delay
               :disconnect-delay disconnect-delay
               :sockjs-handler sockjs-handler)]
        ;; TODO: only send for new session or everytime the client connects???
        (server/send! channel
                      (->> {:status 200
                            :headers {"Content-Type"
                                      "text/event-stream; charset=UTF-8"}
                            :body "\r\n"}
                           (f/h-sid req opts)
                           (f/h-no-cache req opts))
                      false)
        (initialize-session s channel sockjs-handler))
      (session/register-new-channel! session-id channel))))

(defn- polling
  "Handle a polling request. (see `xhr-polling` and `jsonp`)"
  [req session-id sockjs-handler fmt disconnect-delay & [preclude]]
  (server/with-channel req channel
    ;; optional send a preclude
    (when preclude
      (server/send! channel preclude false))
    ;; register the new channel
    (if-not (session/session? session-id)
      (let [s (session/create-polling-session
               session-id fmt
               :sockjs-handler sockjs-handler
               :disconnect-delay disconnect-delay)]
        (initialize-session s channel sockjs-handler))
      (session/register-new-channel! session-id channel))))

(defn xhr-polling
  "handles the xhr polling request"
  [req session-id {:keys [sockjs-handler disconnect-delay] :as opts}]
  (polling req session-id sockjs-handler (fn [m] (str m "\n"))
           disconnect-delay
           (->> {:headers {"Content-Type"
                           "application/javascript; charset=UTF-8"}}
                (f/h-sid req opts)
                (f/h-no-cache req opts)
                (f/xhr-cors req opts)
                (f/status-200 req opts))))

(defn jsonp
  "handles the jsonp polling request"
  [req session-id {:keys [sockjs-handler disconnect-delay] :as opts}]
  (let [cb (or (-> req :params :c)
               (-> req :params :callback))]
    ;; first we need to check if we have a callback parameter
    (cond
     (empty? cb)
     (error-500 "\"callback\" parameter required")
     
     (not (nil? (re-matches #"[^a-zA-Z0-9-_.]" cb)))
     (error-500 "invalid \"callback\" parameter")

     ;; we have a valid callback parameter. Handle the polling request.
     :else
     (polling req session-id sockjs-handler
              (fn [m] (str cb "(" (json/generate-string m) ");\r\n"))
              disconnect-delay
              (->> {:headers {"Content-Type"
                              "application/javascript; charset=UTF-8"}}
                   (f/h-sid req opts)
                   (f/h-no-cache req opts))))))

(def iframe-template-html
  "<!doctype html>
<html><head>
  <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\" />
  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />
</head><body><h2>Don't panic!</h2>
  <script>
    document.domain = document.domain;
    var c = parent.{{ callback }};
    c.start();
    function p(d) {c.message(d);};
    window.onload = function() {c.stop();};
  </script>
")

(def iframe-template
  (str iframe-template-html
       (apply str (repeat (+ (- 1024 (.length iframe-template-html)) 14) " "))
       "\r\n\r\n"))

(defn htmlfile
  "handle the html file request"
  [req session-id {:keys [sockjs-handler heatbeat-delay disconnect-delay
                          response-limit] :as opts}]
  (let [cb (or (-> req :params :c)
               (-> req :params :callback))]
    ;; only handle the polling request if we have a valid callback
    (cond
     (empty? cb)
     (error-500 "\"callback\" parameter required")
     
     (not (nil? (re-matches #"[^a-zA-Z0-9-_.]" cb)))
     (error-500 "invalid \"callback\" parameter")

     :else
     (let [fmt-f #(str "<script>\np("
                       (json/generate-string %)
                       ");\n</script>\r\n")]
       (server/with-channel req channel
         ;; send a preclude before every request
         (server/send! channel
                       (->> {:status 200
                             :headers {"Content-Type"
                                       "text/html; charset=UTF-8"}
                             :body (.replace iframe-template
                                             "{{ callback }}" cb)}
                            (f/h-sid req opts)
                            (f/h-no-cache req opts)
                            (f/status-200 req opts))
                       false)
         (if-not (session/session? session-id)
           (let [s (session/create-streaming-session
                    session-id fmt-f
                    :sockjs-handler sockjs-handler
                    :heatbeat-delay heatbeat-delay
                    :disconnect-delay disconnect-delay
                    :response-limit response-limit)]
             (initialize-session s channel sockjs-handler))
           (session/register-new-channel! session-id channel)))))))


(defn common-send [req session-id sockjs-handler read-body-f success-msg]
  (try
    (let [body (read-body-f req)]
      (if (= (.length body) 0)
        (error-500 "Payload expected.")
        (let [messages (json/parse-string body)
              s (session/->session session-id)]
          (if (and (not (nil? s))
                   (= :open (:ready-state s)))
            (do
              ;; call on message handler
              (session/update-session!
               session-id
               (fn [s]
                 (reduce #(session/on-message sockjs-handler %1 %2)
                         s messages)))
              ;; return success message
              success-msg)
            ;; if session is closed return a 404
            response-404))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (error-500 "Broken JSON encoding."))
    (catch Exception e
      (.printStackTrace e)
      (error-500 "unkown error"))))

(defn xhr-send [req session-id {:keys [sockjs-handler] :as opts}]
  (common-send
   req session-id sockjs-handler
   (fn [r] (let [b (:body req)]
             (if (nil? b) "" (slurp b))))
   (->> {:status 204
         :headers {"Content-Type" "text/plain; charset=UTF-8"}
         :body ""}
        (f/h-sid req opts)
        (f/h-no-cache req opts)
        (f/xhr-cors req opts))))

(defn jsonp-send [req session-id {:keys [sockjs-handler] :as opts}]
  (common-send
   req session-id sockjs-handler
   (fn [r] (let [b (or (-> req :params :d) (:body req))]
             (cond
              (nil? b) ""
              (string? b) b
              :else (slurp b))))
   (->> {:status 200
         :headers {"Content-Type" "text/plain; charset=UTF-8"}
         :body "ok"}
        (f/h-sid req opts)
        (f/h-no-cache req opts))))
