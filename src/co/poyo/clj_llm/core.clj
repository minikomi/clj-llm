(ns co.poyo.clj-llm.core
  "Clean, simple API for LLM interactions supporting both Clojure and Babashka.
   
   Basic usage:
   
   ;; Simple text generation
   (generate provider \"Hello world\")
   
   ;; With options
   (generate provider \"Be creative\" {:temperature 0.9})
   
   ;; Structured output
   (generate provider \"Extract data\" {:schema [:map [:name :string]]})"
  (:require
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [clojure.core.async :as a :refer [go-loop <! >! <!! chan]]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [cheshire.core :as json]))

;; ──────────────────────────────────────────────────────────────
;; Response type
;; ──────────────────────────────────────────────────────────────

(defrecord Response [chunks events text usage structured]
  clojure.lang.IDeref
  (deref [_] @text))

;; ──────────────────────────────────────────────────────────────
;; Internal helpers
;; ──────────────────────────────────────────────────────────────

(defn- parse-structured-output
  "Parse the response as JSON when schema is provided"
  [text schema]
  (try
    (let [parsed (json/parse-string text true)]
      (if schema
        (let [result (m/decode schema parsed mt/json-transformer)]
          (if (m/validate schema result)
            result
            (throw (errors/error
                    "Schema validation failed"
                    {:schema schema
                     :value result
                     :errors (me/humanize (m/explain schema result))}))))
        parsed))
    (catch clojure.lang.ExceptionInfo e
      ;; Re-throw our own errors
      (throw e))
    (catch Exception e
      (throw (errors/error
              "Failed to parse structured output"
              {:input text})))))

(defn- build-messages
  "Build messages array from prompt and options"
  [prompt {:keys [system-prompt messages schema] :as opts}]
  (cond
    ;; User provided full messages array
    messages messages

    ;; Build messages from system + user prompt
    :else (let [effective-prompt (if (and prompt schema)
                                   (str prompt "\n\nRespond with valid JSON.")
                                   prompt)]
            (cond-> []
              system-prompt (conj {:role "system" :content system-prompt})
              effective-prompt (conj {:role "user" :content effective-prompt})))))

;; ──────────────────────────────────────────────────────────────
;; Public API
;; ──────────────────────────────────────────────────────────────

(defn prompt
  ([provider prompt-str]
   (prompt provider prompt-str nil))
  ([provider prompt-str opts]
   (let [opts (merge (:default-opts provider) opts)
         model (or (:model opts) (:default-model provider))
         messages (build-messages prompt-str opts)
         source-chan (proto/request-stream provider model messages opts)
         req-start (System/currentTimeMillis)

         ;; Channels with cleanup
         text-chunks-chan (chan (a/dropping-buffer 1024))
         events-chan (chan (a/dropping-buffer 1024))

         ;; Promises
         text-promise (promise)
         usage-promise (promise)
         structured-promise (promise)

         ;; Timeout for cleanup (optional)
         timeout-ms (or (:timeout opts) 30000) ; 30 second default
         timeout-chan (a/timeout timeout-ms)

         ;; Consumer with timeout protection
         _ (go-loop [chunks []]
             (let [[event port] (a/alts! [source-chan timeout-chan])]
               (cond
                 ;; Timeout case
                 (= port timeout-chan)
                 (do
                   (println "Stream timed out, cleaning up")
                   (deliver text-promise (Exception. "Stream timeout"))
                   (when-not (realized? usage-promise)
                     (deliver usage-promise nil))
                   (when-not (realized? structured-promise)
                     (deliver structured-promise (Exception. "Stream timeout")))
                   (a/close! text-chunks-chan)
                   (a/close! events-chan))

                 ;; Normal event
                 event
                 (do
                   (a/offer! events-chan event)
                   (case (:type event)
                     :content (do
                               (a/offer! text-chunks-chan (:content event))
                               (recur (conj chunks (:content event))))
                     :usage (do
                             (deliver usage-promise (assoc event :clj-llm/model model :clj-llm/req-start req-start :clj-llm/req-end (System/currentTimeMillis) :clj-llm/duration (- (System/currentTimeMillis) req-start)))
                             (recur chunks))
                     :error (do
                             (deliver text-promise (errors/stream-error
                                                   "LLM request failed" 
                                                   {:event event
                                                    :request {:model model
                                                             :messages messages
                                                             :started-at req-start
                                                             :provider provider}}))
                             (when-not (realized? usage-promise)
                               (deliver usage-promise nil))
                             (when-not (realized? structured-promise)
                               (deliver structured-promise (Exception. (:error event))))
                             (a/close! text-chunks-chan)
                             (a/close! events-chan))
                     :done (do
                            (deliver text-promise (apply str chunks))
                            (when-not (realized? usage-promise)
                              (deliver usage-promise nil))
                            (a/close! text-chunks-chan)
                            (a/close! events-chan))
                     (recur chunks)))

                 ;; Source closed without event
                 :else
                 (do
                   (deliver text-promise "")
                   (when-not (realized? usage-promise)
                     (deliver usage-promise nil))
                   (when-not (realized? structured-promise)
                     (deliver structured-promise (Exception. "Stream closed unexpectedly")))
                   (a/close! text-chunks-chan)
                   (a/close! events-chan)))))

         ;; Structured output with timeout protection
         _ (if (:schema opts)
             (future
               (try
                 (let [text (deref text-promise timeout-ms ::timeout)]
                   (if (= text ::timeout)
                     (deliver structured-promise (Exception. "Text promise timeout"))
                     (deliver structured-promise
                              (if (instance? Exception text)
                                text
                                (parse-structured-output text (:schema opts))))))
                 (catch Exception e
                   (deliver structured-promise e))))
             (deliver structured-promise
                      (Exception. "Structured output requested but no schema provided")))]

     (->Response text-chunks-chan
                 events-chan
                 text-promise
                 usage-promise
                 structured-promise))))

;; Convinience

(defn generate
  "Generate response from the LLM.

     Args:
         provider - LLM provider instance
         prompt   - String prompt or nil if messages provided in opts
         opts     - Options map (see `prompt` for details)

     Returns:
         Text response or, when schema present, structured data.

     Example:
         (generate openai \"Tell me a joke\") ;=> \"Why did the chicken cross the road? To get to the other side!\""
  ([provider prompt-str]
   (generate provider prompt-str nil))
  ([provider prompt-str opts]
   (let [response (prompt provider prompt-str opts)
         timeout-ms (or (:timeout opts) 
                       (:timeout (:default-opts provider)) 
                       30000)]
     
     (let [result-promise (if (or (:schema opts) (:schema (:default-opts provider)))
                             (:structured response)
                             (:text response))
             result (deref result-promise timeout-ms ::timeout)]
         (cond
           (= result ::timeout)
           (throw (errors/error 
                   (str "LLM request timed out after " timeout-ms "ms")
                   {:timeout-ms timeout-ms
                    :provider provider 
                    :prompt prompt-str 
                    :opts opts}))
           
           (instance? Exception result)
           (throw result)
           
           :else
           result)))))

(defn events
  "Get raw events channel for monitoring LLM interactions.

   Args:
     provider - LLM provider instance
     prompt   - String prompt or nil if messages provided in opts
     opts     - Options map (see `prompt` for details)

   Returns:
     Channel of raw events with type :content, :usage, :error, etc.

   Example:
     (let [events (events openai \"Hello\")]
       (go-loop []
         (when-let [event (<! events)]
           (println \"Event:\" (:type event))
           (recur))))"
  ([provider prompt-str]
   (events provider prompt-str nil))
  ([provider prompt-str opts]
   (:events (prompt provider prompt-str opts))))

(defn stream
    "Stream text chunks from the LLM.

     Args:
         provider - LLM provider instance
         prompt   - String prompt or nil if messages provided in opts
         opts     - Options map (see `prompt` for details)

     Returns:
         Channel of text chunks as they are generated.

     Example:
         (let [chunks (stream openai \"Tell me a story\")]
         (go-loop []
             (when-let [chunk (<! chunks)]
             (print chunk)
             (recur))))"
    ([provider prompt-str]
     (stream provider prompt-str nil))
    ([provider prompt-str opts]
     (:chunks (prompt provider prompt-str opts))))

