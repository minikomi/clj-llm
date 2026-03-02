(ns co.poyo.clj-llm.util)

(defn run-daemon!
  "Run f on a daemon thread. Won't prevent JVM exit."
  [f]
  (doto (Thread. ^Runnable f)
    (.setDaemon true)
    (.start)))
