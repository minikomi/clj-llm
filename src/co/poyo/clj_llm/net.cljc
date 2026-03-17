(ns co.poyo.clj-llm.net
  (:require
   #?(:bb  [co.poyo.clj-llm.impl.net.bb :as impl]
      :clj [co.poyo.clj-llm.impl.net.jvm :as impl])))

(defn post-stream
  "Blocking POST. Returns {:status int :body InputStream}.
   Throws on connection errors."
  [url headers body]
  (impl/post-stream url headers body))
