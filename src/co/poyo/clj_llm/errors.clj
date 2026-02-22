(ns co.poyo.clj-llm.errors
  "Simple error handling for clj-llm.
   
   Errors carry an :error-type keyword in ex-data for programmatic dispatch:
     :llm/rate-limit, :llm/invalid-key, :llm/network-error,
     :llm/server-error, :llm/invalid-request, :llm/unknown")

(defn- status->error-type
  "Map HTTP status code to error type keyword."
  [status]
  (case (int status)
    (401 403) :llm/invalid-key
    429       :llm/rate-limit
    404       :llm/invalid-request
    (400 422) :llm/invalid-request
    (500 502 503 504) :llm/server-error
    :llm/unknown))

(defn error
  "Create an LLM error with optional data.
   If :error-type is not provided, defaults to :llm/unknown."
  ([msg] (error msg nil))
  ([msg data]
   (ex-info msg (merge {:error-type :llm/unknown} data))))

(defn error-type
  "Get the error type keyword from an exception.
   Returns :llm/rate-limit, :llm/invalid-key, :llm/network-error, :llm/server-error,
   :llm/invalid-request, or :llm/unknown."
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (:error-type (ex-data e))))

(defn retry-after
  "Get the retry-after value in ms from a rate-limit error, or nil."
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (:retry-after (ex-data e))))

(defn parse-http-error
  "Convert HTTP response to exception with proper :error-type."
  [provider status body]
  (let [et (status->error-type status)
        msg (case (int status)
              (401 403) "Invalid API key"
              429       "Rate limit exceeded"
              404       "Resource not found"
              (400 422) "Invalid request"
              (500 502 503 504) "Server error"
              (str "HTTP " status))]
    (error (str provider ": " msg)
           {:error-type  et
            :status      status
            :body        body
            :retry-after (get-in body [:error :retry_after])})))
