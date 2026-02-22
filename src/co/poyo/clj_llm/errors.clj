(ns co.poyo.clj-llm.errors
  "Simple error handling for clj-llm.")

(defn error
  "Create an LLM error with optional data"
  ([msg] (error msg nil))
  ([msg data]
   (ex-info msg (merge {:type :llm-error} data))))

(defn error-type
  "Get the error type keyword from an exception.
   Returns :llm/rate-limit, :llm/invalid-key, :llm/network-error, :llm/server-error,
   :llm/invalid-request, or :llm/unknown."
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (let [{:keys [status type]} (ex-data e)]
      (case status
        401 :llm/invalid-key
        403 :llm/invalid-key
        429 :llm/rate-limit
        404 :llm/invalid-request
        (400 422) :llm/invalid-request
        (500 502 503 504) :llm/server-error
        (if (= type :llm-error) :llm/unknown nil)))))

(defn retry-after
  "Get the retry-after value in ms from a rate-limit error, or nil."
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (:retry-after (ex-data e))))

(defn parse-http-error
  "Convert HTTP response to exception"
  [provider status body]
  (let [msg (case status
              401 "Invalid API key"
              403 "Access forbidden"
              404 "Resource not found"
              429 "Rate limit exceeded"
              (400 422) "Invalid request"
              (500 502 503 504) "Server error"
              (str "HTTP " status))]
    (error (str provider ": " msg)
           {:status status
            :body body
            :retry-after (get-in body [:error :retry_after])})))
