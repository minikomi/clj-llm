(ns co.poyo.clj-llm.errors
  "Simple error handling for clj-llm.
   
   Errors carry an :error-type keyword in ex-data for programmatic dispatch:
     :llm/rate-limit, :llm/invalid-key, :llm/network-error,
     :llm/server-error, :llm/invalid-request, :llm/unknown")

;; Single source of truth for HTTP status → error type + message
(def ^:private status-errors
  {401 {:type :llm/invalid-key    :msg "Invalid API key"}
   403 {:type :llm/invalid-key    :msg "Invalid API key"}
   404 {:type :llm/invalid-request :msg "Resource not found"}
   429 {:type :llm/rate-limit     :msg "Rate limit exceeded"}
   400 {:type :llm/invalid-request :msg "Invalid request"}
   422 {:type :llm/invalid-request :msg "Invalid request"}
   500 {:type :llm/server-error   :msg "Server error"}
   502 {:type :llm/server-error   :msg "Server error"}
   503 {:type :llm/server-error   :msg "Server error"}
   504 {:type :llm/server-error   :msg "Server error"}})

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
  (let [{:keys [type msg]} (get status-errors (int status)
                                {:type :llm/unknown :msg (str "HTTP " status)})]
    (error (str provider ": " msg)
           {:error-type  type
            :status      status
            :body        body
            :retry-after (get-in body [:error :retry_after])})))
