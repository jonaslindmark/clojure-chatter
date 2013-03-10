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

(defn deserialize [buffer]
  (defn protobuf-deserialize [data]
    (protobuf-load ChatMessage data))
  (defn gloss-deserialize-msg [data]
    (let [buffer (contiguous data)
          bytes (byte-array (.remaining buffer))]
      (.get buffer bytes 0 (alength bytes))
      (protobuf-deserialize bytes)))
  (when buffer
    (gloss-deserialize-msg buffer)))

;; == Server ==
(defn start-server [port]
  (def broadcast-ch (channel))
  (defn server-handler [ch ci]
    (defn get-client-id [buffer]
      (let [msg (deserialize buffer)]
        (get-in msg [:sender :id])))
    (defn setup [buffer]
      (let [client-id (get-client-id buffer)
            parsed-in-channel (map* deserialize ch)
            output-channel (channel)
            to-this-client (filter* #(= client-id (get % :receiver_id)) output-channel)
            serialized-out (map* serialize to-this-client)]
        (println "Client " client-id " connected to the server!")
        (defn on-close []
          (println "Closing bridge for " client-id)
          (close output-channel))
        (on-closed ch on-close)
        (siphon parsed-in-channel broadcast-ch)
        (siphon broadcast-ch output-channel)
        (siphon serialized-out ch)))
    (receive ch setup))
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
  (defn msg-parser [buffer]
    (let [message (deserialize buffer)]
      (handle-get-message message)))
  (let [channel (get-client-channel)]
    (on-closed channel #(println (str id " closed client")))
    (on-drained channel #(println (str id " client channel drained")))
    (receive-all channel msg-parser)
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

;; == CLI interface ==
(defn parse-args [args]
  (cli args
       ["-s" "--server" "Starts the server" :default false :flag true]
       ["-c" "--client" "Starts a client" :default false :flag true]
       ["-h" "--host" "Specify host" :default "127.0.0.1"]
       ["-p" "--port" "Specify port" :default default-port]
       ["--help" "Shows help" :flag true :default false]))

(defn client-prompt [host port]
  (defn get-input [prompt]
    (print prompt)
    (.flush *out*)
    (read-line))
  (defn send-messages [client]
    (let [message (get-input "Message ('q' to quit): ")]
      (if (= message "q")
        (do
          (println "Quitting")
          (close-client client))
        (let [id (get-input "To id: ")]
          (send-message client message id)
          (future (send-messages client))))))
  (let [id (get-input "Your id: ")
        name (get-input "Your name: ")
        client (start-a-client id name host port)]
    (println "Connected")
    (future (send-messages client))
    nil))

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
