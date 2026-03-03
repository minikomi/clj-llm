(ns co.poyo.clj-llm.net
  #?(:bb (:require [babashka.http-client :as http]))
  #?(:clj (:import (java.net.http HttpClient HttpClient$Version HttpRequest$BodyPublishers HttpRequest HttpResponse$BodyHandlers)
                   (java.net URI)
                   (java.time Duration))))

#?(:clj
   (def ^:private http-client
     (delay
       (-> (HttpClient/newBuilder)
           (.version HttpClient$Version/HTTP_2)
           (.connectTimeout (Duration/ofSeconds 10))
           (.build)))))

(defn post-stream
  "Blocking POST. Returns {:status int :body InputStream}.
   Throws on connection errors."
  [url headers body]
  #?(:bb
     (let [{:keys [status body]} (http/post url {:headers headers
                                                 :body body
                                                 :as :stream
                                                 :throw false})]
       {:status status :body body})
     :clj
     (let [req (-> (HttpRequest/newBuilder)
                   (.uri (URI/create url))
                   (.timeout (Duration/ofSeconds 30))
                   (.POST (HttpRequest$BodyPublishers/ofString body)))]
       (doseq [[k v] headers]
         (.header req k v))
       (let [response (.send @http-client
                            (.build req)
                            (HttpResponse$BodyHandlers/ofInputStream))]
         {:status (.statusCode response)
          :body (.body response)}))))
