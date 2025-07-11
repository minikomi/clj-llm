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

(defn- collect-chunks
  "Collect all content chunks from an event channel into a string"
  [events-chan]
  (loop [chunks []]
    (if-let [event (<!! events-chan)]
      (case (:type event)
        :content (recur (conj chunks (:content event)))
        :error (throw (errors/stream-error
                       "Error during streaming"
                       :event event
                       :cause (when (:error event)
                                (Exception. (:error event)))))
        :done chunks
        (recur chunks))
      chunks)))

(defn- collect-full-response
  "Collect all events from a channel"
  [events-chan]
  (loop [events []]
    (if-let [event (<!! events-chan)]
      (recur (conj events event))
      events)))

(defn- parse-structured-output
  "Parse the response as JSON when schema is provided"
  [text schema]
  (try
    (let [parsed (json/parse-string text true)]
      (if schema
        (let [result (m/decode schema parsed mt/json-transformer)]
          (if (m/validate schema result)
            result
            (throw (errors/schema-validation-error
                    schema
                    result
                    (me/humanize (m/explain schema result))))))
        parsed))
    (catch clojure.lang.ExceptionInfo e
      ;; Re-throw our own errors
      (throw e))
    (catch Exception e
      (throw (errors/parse-error
              "Failed to parse structured output"
              :input text
              :cause e)))))

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
;; Derrived backends
;; ──────────────────────────────────────────────────────────────

(defn with-config [backend opts]
    "Wraps a backend with additional configuration options.
     
     This is useful for setting default options like model, temperature, etc.
     
     Example:
         (def my-backend (with-config openai-backend {:model \"gpt-3.5-turbo\" :temperature 0.7}))"
    (assoc backend :default-opts
              (merge (:default-opts backend) opts)))

;; ──────────────────────────────────────────────────────────────
;; Channel utilities
;; ──────────────────────────────────────────────────────────────

(defn consume!
  "Consume a channel, applying f to each value. 
   Returns a channel that closes when consumption is complete.
   
   Example:
     (consume! (llm/stream ai \"Tell a story\") print)"
  [ch f]
  (go-loop []
    (when-let [v (<! ch)]
      (f v)
      (recur))))

(defn collect
  "Collect all values from a channel into a vector.
   Blocks until the channel is closed.
   
   Example:
     (collect (llm/stream ai \"List items\"))
     ;=> [\"1. \" \"First item\" \"\\n2. \" \"Second item\"]"
  [ch]
  (loop [acc []]
    (if-let [v (<!! ch)]
      (recur (conj acc v))
      acc)))

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
                                                   :event event
                                                   :request {:model model
                                                            :messages messages
                                                            :started-at req-start
                                                            :provider provider}))
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
                       30000)
         result-promise (if (or (:schema opts) (:schema (:default-opts provider)))
                         (:structured response)
                         (:text response))
         result (deref result-promise timeout-ms ::timeout)]
     (cond
       (= result ::timeout)
       (throw (errors/timeout-error 
               (str "LLM request timed out after " timeout-ms "ms")
               timeout-ms
               {:provider provider :prompt prompt-str :opts opts}))
       
       (instance? Exception result)
       (throw result)
       
       :else
       result))))

(defn structured
    "Get structured output from the LLM response.

     Args:
         provider - LLM provider instance
         prompt   - String prompt or nil if messages provided in opts
         opts     - Options map (see `prompt` for details)

     Returns:
            Parsed structured data as per provided schema.

     Example:
         (let [data (structured openai \"Extract data\" {:schema [:map [:name :string]]})]
         @data) ;=> {:name \"John\"}"
    ([provider prompt-str schema]
     (structured provider prompt-str schema nil))
    ([provider prompt-str schema opts]
     @(:structured (prompt provider prompt-str (assoc opts :schema schema)))))

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

(defn conversation
  "Create a stateful conversation with the LLM.
  
   Args:
     provider - LLM provider instance
     opts - Options map with optional :system-prompt
     
   Returns:
     Map with :prompt function, :messages atom, and :clear function
     
   Example:
     (def chat (conversation openai {:system-prompt \"You're a helpful assistant\"}))
     ((:prompt chat) \"Hello!\")  ;=> \"Hi! How can I help you?\"
     ((:prompt chat) \"What's 2+2?\") ;=> \"2+2 equals 4\"
     @(:messages chat) ;=> [{:role :system ...} {:role :user ...} ...]
     ((:clear chat)) ;=> resets conversation"
  [provider & {:keys [system-prompt] :as opts}]
  (let [messages (atom (if system-prompt
                        [{:role :system :content system-prompt}]
                        []))]
    {:prompt (fn 
               ([text]
                (swap! messages conj {:role :user :content text})
                (let [response (generate provider nil {:messages @messages})]
                  (swap! messages conj {:role :assistant :content response})
                  response))
               ([text opts]
                (swap! messages conj {:role :user :content text})
                (let [response (generate provider nil 
                                       (assoc opts :messages @messages))]
                  (swap! messages conj {:role :assistant :content response})
                  response)))
     :messages messages
     :clear #(reset! messages (if system-prompt
                               [{:role :system :content system-prompt}]
                               []))}))
