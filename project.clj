(defproject chatter "0.0.1"
  :description "Chattie chattie"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/tools.cli "0.2.2"]
                 [protobuf "0.6.2"]
                 [aleph "0.3.0-beta12"]]
  :plugins [[lein-protobuf "0.3.1"]]
  :main chatter.core)
