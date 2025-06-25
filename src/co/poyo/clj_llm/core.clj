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
;; Public API
;; ──────────────────────────────────────────────────────────────

(defn prompt
  "Make a request returning a rich response object.
   
   Args:
     provider - LLM provider instance
     prompt   - String prompt or nil if messages provided in opts
     opts     - Same options as generate
     
   Returns:
     Response map that:
     - Implements IDeref (deref returns generated text)
     - Contains promises/channels for different aspects:
       :text       - Promise of full text response
       :chunks     - Channel of text chunks  
       :events     - Channel of raw events
       :usage      - Promise of token usage stats
       :structured - Promise of structured data (if schema provided)
       
   Example:
     (def resp (prompt openai \"Hello\"))
     @resp ;=> \"Hello! How can I help you?\"
     
     @(:usage resp) ;=> {:prompt-tokens 10 :completion-tokens 20}"
  ([provider prompt-str]
   (prompt provider prompt-str nil))
  ([provider prompt-str opts]
   (let [model (or (:model opts) (:default-model provider))
         messages (build-messages prompt-str opts)

         ;; Create multiple channels tapped from the same source
         source-chan (proto/request-stream provider model messages opts)
         mult-source (a/mult source-chan)

         ;; Create tapped channels
         text-chan (chan 1024)
         chunks-chan (chan 1024)
         events-chan (chan 1024)
         usage-chan (chan 1024)

         ;; Tap all channels
         _ (a/tap mult-source text-chan)
         _ (a/tap mult-source chunks-chan)
         _ (a/tap mult-source events-chan)
         _ (a/tap mult-source usage-chan)

         ;; Create promises for blocking access
         text-promise (promise)
         usage-promise (promise)
         structured-promise (promise)

         ;; Process text channel
         _ (go-loop [chunks []]
             (if-let [event (<! text-chan)]
               (case (:type event)
                 :content (recur (conj chunks (:content event)))
                 :error (deliver text-promise (errors/stream-error
                                               "LLM request failed"
                                               :event event))
                 :done (deliver text-promise (apply str chunks))
                 (recur chunks))
               (deliver text-promise "")))

         ;; Process usage channel
         _ (go-loop []
             (if-let [event (<! usage-chan)]
               (case (:type event)
                 :usage (deliver usage-promise event)
                 (recur))
               (deliver usage-promise nil)))

         ;; Transform chunks channel to just text
         text-chunks-chan (chan)
         _ (go-loop []
             (if-let [event (<! chunks-chan)]
               (do
                 (when (= :content (:type event))
                   (>! text-chunks-chan (:content event)))
                 (when (= :done (:type event))
                   (a/close! text-chunks-chan))
                 (when-not (= :done (:type event))
                   (recur)))
               (a/close! text-chunks-chan)))

         ;; Handle structured output if schema provided
         _ (when (:schema opts)
             (future
               (try
                 (let [text @text-promise]
                   (if (instance? Exception text)
                     (deliver structured-promise text)
                     (deliver structured-promise
                              (parse-structured-output text (:schema opts)))))
                 (catch Exception e
                   (deliver structured-promise e)))))]



     {
      ;; streaming
      :chunks text-chunks-chan
      :events events-chan
      ;; blocking
      :text text-promise
      :usage usage-promise
      :structured structured-promise
      }
     )))

;; Convinience

(defn generate
    "Generate text response from the LLM.

     Args:
         provider - LLM provider instance
         prompt   - String prompt or nil if messages provided in opts
         opts     - Options map (see `prompt` for details)

     Returns:
         Full text response as a string.

     Example:
         (generate openai \"Tell me a joke\") ;=> \"Why did the chicken cross the road? To get to the other side!\""
    ([provider prompt-str]
     (generate provider prompt-str nil))
    ([provider prompt-str opts]
     (let [response (prompt provider prompt-str opts)]
         @(:text response))))

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
     (structured provider prompt-str nil))
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
