(ns co.poyo.clj-llm.impl.net.bb
  (:require
   [babashka.http-client :as http]))

;; Prefer IPv4 — many hosts have broken IPv6 connectivity
(System/setProperty "java.net.preferIPv4Stack" "true")

(defn post-stream
  "Blocking POST. Returns {:status int :body InputStream}.
   Throws on connection errors."
  [url headers body]
  (let [{:keys [status body]} (http/post url {:headers headers
                                              :body body
                                              :as :stream
                                              :throw false})]
    {:status status
     :body   body}))
