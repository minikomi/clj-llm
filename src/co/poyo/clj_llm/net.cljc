(ns co.poyo.clj-llm.net
  #?(:bb  (:require [babashka.http-client :as http])     ; babashka
     :clj (:require [org.httpkit.client :as http]))      ; JVM
  #?(:clj (:import (java.io InputStream))))               ; hint for IDEs

(defn post-stream
  "POST `url` with `headers` and string `body`.
   Calls `cb` with {:status int :body InputStream :error ex?}."
  [url headers body cb]
  #?(:bb
     (let [{:keys [status body error]} (http/post url {:headers headers
                                                       :body    body
                                                       :as      :stream})]
       (cb {:status status :body body :error error}))

     :clj
     (http/request
      {:method  :post
       :url     url
       :headers headers
       :body    body
       :as      :stream
       :async?  true}
      (fn [{:keys [status body error]}]
        (cb {:status status :body body :error error})))))
