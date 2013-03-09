(ns chatter.core
  (:use protobuf.core
        lamina.core
        gloss.core
        gloss.io
        clojure.tools.cli
        aleph.tcp)
  (:gen-class :main true))

(def Chatter (protodef chattie.Chattie$Chatter))
(def ChatMessage (protodef chattie.Chattie$ChatMessage))
(def default-port 10000)

;; Frame type to send over the wire
(def frame (finite-block :int32))

;; == Serialization ==
(defn serialize [chatmessage]
  (protobuf-dump (protobuf ChatMessage chatmessage)))

(defn deserialize [data]
  (protobuf-load ChatMessage data))

(defn gloss-deserialize-msg [data]
  (let [buffer (contiguous data)
        bytes (byte-array (.remaining buffer))]
    (.get buffer bytes 0 (alength bytes))
    (deserialize bytes)))

(defn msg-parser [callback]
  (fn [buffer]
    (when buffer
      (let [msg (gloss-deserialize-msg buffer)]
        (callback msg)))))

;; == Server ==

(def broadcast-ch (channel))

(defn parser [b]
  (when b
    (gloss-deserialize-msg b)))

(defn server-handler [ch ci]
  (defn get-client-id [buffer]
    (let [msg (parser buffer)]
      (get-in msg [:sender :id])))
  (defn setup [buffer]
    (let [client-id (get-client-id buffer)
          to-this-client (filter* #(= client-id (get % :receiver_id)) broadcast-ch)
          serialized-out (map* serialize to-this-client)
          parsed-in-channel (map* parser ch)]
      (siphon parsed-in-channel broadcast-ch)
      (siphon serialized-out ch)))
  (receive ch setup))

(defn start-server [port]
  (start-tcp-server server-handler {:port port :frame frame}))

;; == Client ==

(defn start-a-client [id, name, host, port]
  (defn get-client-channel []
    (wait-for-result
      (tcp-client {:host host
                   :port port
                   :frame frame})))
  (defn handle-get-message [msg]
    (let [name (get-in msg [:sender :name])
          id (get-in msg [:sender :id])
          text (:message msg)]
      (println (str name "(id " id ") says " text))))
  (let [channel (get-client-channel)]
    (on-closed channel #(println (str id " closed client")))
    (on-drained channel #(println (str id " client channel drained")))
    (receive-all channel (msg-parser handle-get-message))
    {:channel channel :id id :name name}))

(defn send-message [client message recipient_id]
  (let [sender_name (get client :name)
        sender_id (get client :id)
        msg (hash-map :sender {:name sender_name :id sender_id}
                      :message message
                      :receiver-id recipient_id)
        data (serialize msg)
        channel (get client :channel)]
    (enqueue channel data)))

(defn close-client [client]
  (let [channel (get client :channel)]
    (close channel)))

(defn client-prompt [host port]
  (defn get-id []
    (print "Your id: ")
    (.flush *out*)
    (read-line))
  (defn get-name []
    (print "Your name ")
    (.flush *out*)
    (read-line))
  (defn send-messages [client]
    (defn get-message []
      (print "Message ('q' to quit): ")
      (.flush *out*)
      (read-line))
    (defn get-id []
      (print "Recipient id: ")
      (.flush *out*)
      (read-line))
    (let [message (get-message)]
      (if (= message "q")
        (do
          (println "Quitting")
          (close-client client))
        (let [id (get-id)]
          (send-message client message id)
          (future (send-messages client))))))
  (let [id (get-id)
        name (get-name)
        client (start-a-client id name host port)]
    (println "Connected")
    (future (send-messages client))))

(defn parse-args [args]
  (cli args
       ["-s" "--server" "Starts the server" :default false :flag true]
       ["-c" "--client" "Starts a client" :default false :flag true]
       ["-h" "--host" "Specify host" :default "127.0.0.1"]
       ["-p" "--port" "Specify port" :default default-port]
       ["--help" "Shows help" :flag true :default false]))

(defn -main [& args]
  (let [[options additional instructions] (parse-args args)]
    (when (:help options)
      (println instructions)
      (System/exit 0))
    (when (:server options)
      (let [port (:port options)
            host (:host options)]
        (println "Starting server on port" port)
        (start-server port)
        (println "Server started")))
    (when (:client options)
      (let [port (:port options)
            host (:host options)]
        (println "Starting client to " host ":" port)
        (client-prompt host port)))
  ))
