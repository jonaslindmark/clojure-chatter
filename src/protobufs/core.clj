(ns protobufs.core
  (:use [protobuf.core]
        [lamina.core]
        [gloss.core]
        [gloss.io]
        [aleph.tcp]))

(def Chatter (protodef chattie.Chattie$Chatter))
(def ChatMessage (protodef chattie.Chattie$ChatMessage))

;; Port to listen for TCP connections
(def port 10000)

;; Frame type to send over the wire
(def frame (gloss.core/finite-block :int32))

(defn serialize [chatmessage]
  (protobuf-dump (protobuf ChatMessage chatmessage)))

(defn deserialize [data]
  (protobuf-load ChatMessage data))

(defn gloss-deserialize-msg [data]
  (let [buffer (gloss.io/contiguous data)
        bytes (byte-array (.remaining buffer))]
    (.get buffer bytes 0 (alength bytes))
    (deserialize bytes)))

(defn handler
  [channel client-info]
  (lamina.core/receive-all
    channel
    (fn [buffer]
      (when buffer
        (let [msg (gloss-deserialize-msg buffer)]
          (println (str "Got message " msg " from " client-info)))))))

(defn start-server []
  (aleph.tcp/start-tcp-server handler {:port port :frame frame}))

;; Example message from example chatter
(def chat-message {:sender {:name "hejsan"
                            :id "someid123"}
                   :message "some really interesting message"
                   :receiver-id "someotherid321"})

(defn get-client-channel []
  (let [channel (aleph.tcp/tcp-client {:host "127.0.0.1"
                                       :port port
                                       :frame frame})]
    (lamina.core/wait-for-result channel)))

(defn -main
  "Main entry point"
  [& args]
  (start-server)
  ;; Send test message
  (let [client-channel (get-client-channel)]
    (println (str "Sending message!" chat-message))
    (lamina.core/enqueue client-channel (serialize chat-message))))
