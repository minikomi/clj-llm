#!/usr/bin/env bb

(ns chat-repl
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<! go]]))

(openai/register-backend!)

(defn -main [& args]
  (let [model-name (first *command-line-args*)]

    (when-not model-name (println "Usage: chat-repl <model-name>") (System/exit 1))

    (let [conv (llm/conversation (keyword model-name))]
      (println "Type your message (or press Enter to exit):")
      (loop []
        (println "---------------------------------------")
        (print "> ")
        (flush)
        (let [input (read-line)]
          (if (empty? input)
            (do
              (println "Exiting conversation.")
              (System/exit 0))
            (let [chunks-chan (:chunks ((:prompt conv) input))]

              (try
                (loop []
                  (let [chunk (<! chunks-chan)]
                    (when (:content chunk)
                      (do
                        (print (-> chunk :content))
                        (flush)
                        (recur)))))
                (catch Exception e
                  (println "Error during conversation:" e)))
              (print "\n\n")
              (flush)
              (recur))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
