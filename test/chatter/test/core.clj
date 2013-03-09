(ns chatter.test.core
  (:use [protobufs.core])
  (:use [clojure.test]))

(def chatter (hash-map :name "hejsan" :id "someid123"))
(def chat_message (hash-map :sender chatter :message "some really interesting message" :receiver_id "someotherid321"))

(deftest chatmessage_serialization
         (let [serialized (serialize chat_message)
               deserialized (deserialize serialized)]
           (is (= chat_message deserialized) "Serialize")
           (is (not= serialized deserialized) "Does something!")))
