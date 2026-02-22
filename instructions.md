# AI Provider Wrapper Refactoring Specification

## Objective
Refactor the AI provider wrapper library to use a clean, composable Clojure design with explicit boundaries between library options and provider-specific options.

## Core Design Principles

1. **Explicit boundaries** - Use `:provider/opts` to clearly separate passthrough options
2. **Namespaced keywords** - Use `:mylib/*` for library options, `:openai/*`, `:anthropic/*` etc for provider-specific
3. **Malli validation** - Validate at boundaries for safety and documentation
4. **Just data** - Providers and options are plain maps, manipulable with standard functions
5. **Defaults mirror usage** - Same structure for defaults and runtime options

## Primary Interface

### The `prompt` function

```clojure
(defn prompt
  "Send a prompt to an AI provider and receive a stream.
   
   provider - A provider instance (created with openai/backend, anthropic/backend, etc)
   input    - The user's input string
   opts     - Options map with:
              :mylib/system   - System prompt
              :mylib/schema   - Response schema  
              :mylib/messages - Message history
              :provider/opts  - Provider-specific options (passthrough)"
  [provider input opts]
  ;; Implementation will:
  ;; 1. Deep merge provider defaults with opts
  ;; 2. Validate library options
  ;; 3. Pass :provider/opts directly to provider without modification
  )
```

## Schema Definitions

Add these Malli schemas to define and validate options:

```clojure
(ns mylib.ai.schemas
  (:require [malli.core :as m]))

;; Library-level options (what we control)
(def LibraryOpts
  [:map
   [:mylib/system {:optional true
                   :description "System prompt for the AI"} 
    :string]
   [:mylib/schema {:optional true
                   :description "Schema for structured responses"}
    :map]
   [:mylib/messages {:optional true
                     :description "Conversation history"}
    [:vector [:map
              [:role [:enum :user :assistant :system]]
              [:content :string]]]]])

;; Combined opts for prompt function
(def PromptOpts
  [:map
   [:mylib/system {:optional true} :string]
   [:mylib/schema {:optional true} :map]  
   [:mylib/messages {:optional true} [:vector :map]]
   [:provider/opts {:optional true
                    :description "Provider-specific options (passthrough)"}
    :map]])

;; Provider-specific schemas (for validation when known)
(def OpenAIOpts
  [:map {:closed false}  ; Allow extra keys we don't know about
   [:openai/model :string]
   [:openai/temperature {:optional true} [:double {:min 0 :max 2}]]
   [:openai/max-tokens {:optional true} :int]
   [:openai/stream {:optional true} :boolean]])

(def AnthropicOpts
  [:map {:closed false}
   [:anthropic/model :string]
   [:anthropic/max-tokens {:optional true} :int]
   [:anthropic/version {:optional true} :string]])
```

## Provider Implementation

```clojure
(defprotocol AIProvider
  "Protocol for AI provider implementations"
  (make-request [provider input opts] 
    "Transform prompt call into provider-specific API request"))

(defrecord OpenAI [api-key defaults]
  AIProvider
  (make-request [this input opts]
    ;; Merge defaults with runtime opts
    (let [final-opts (deep-merge defaults opts)
          lib-opts (select-keys final-opts [:mylib/system :mylib/schema :mylib/messages])
          provider-opts (:provider/opts final-opts)]
      ;; Transform to OpenAI API format
      ;; Use provider-opts directly without filtering
      )))

;; Constructor with validation
(defn backend 
  "Create OpenAI provider with optional defaults.
   
   config map:
   :api-key - Required OpenAI API key
   :defaults - Optional default options (same shape as prompt opts)"
  [{:keys [api-key defaults]}]
  (assert api-key "API key required")
  ;; Validate defaults if provided
  (when defaults
    (m/assert PromptOpts defaults))
  (->OpenAI api-key defaults))
```

## Usage Examples to Support

```clojure
;; Basic usage
(def openai (backend {:api-key (env "OPENAI_KEY")}))

(prompt openai "Hello" {})

;; With defaults
(def openai-with-defaults
  (backend {:api-key (env "OPENAI_KEY")
             :defaults {:mylib/system "You are helpful"
                       :provider/opts {:openai/model "gpt-4o"
                                      :openai/temperature 0.7}}}))

;; Override defaults at call time
(prompt openai-with-defaults 
        "Explain recursion"
        {:mylib/system "You are a teacher"  ; overrides default
         :provider/opts {:openai/temperature 0.9}})  ; merges with defaults

;; Derived providers (should work with simple data manipulation)
(def openai-creative
  (assoc-in openai-with-defaults 
            [:defaults :provider/opts :openai/temperature] 
            0.9))

;; Provider-specific options pass through unchanged
(prompt openai
        "Generate code"
        {:provider/opts {:openai/response-format {:type "json_object"}
                        :openai/tool-choice "auto"
                        :openai/custom-beta-feature true}})  ; Unknown keys pass through
```

## Migration Steps

1. **Update namespaces** - Convert all option keys to namespaced keywords
   - Library options: `:system` → `:mylib/system`
   - Provider options: `:model` → `:openai/model` (when in provider/opts)

2. **Restructure options** - Move provider-specific options under `:provider/opts`
   - Before: `{:system "..." :model "gpt-4" :temperature 0.7}`
   - After: `{:mylib/system "..." :provider/opts {:openai/model "gpt-4" :openai/temperature 0.7}}`

3. **Add Malli schemas** - Define schemas for validation and documentation

4. **Update provider records** - Store defaults with same structure as runtime opts

5. **Implement deep-merge** - For combining defaults with runtime options
   ```clojure
   (defn deep-merge [& maps]
     (apply merge-with
            (fn [v1 v2]
              (if (and (map? v1) (map? v2))
                (deep-merge v1 v2)
                v2))
            maps))
   ```

## Testing Checklist

- [ ] Defaults merge correctly with runtime options
- [ ] Provider-specific options pass through unchanged
- [ ] Unknown provider options are not filtered out
- [ ] Malli validation catches invalid library options
- [ ] Derived providers work via simple data manipulation
- [ ] Namespaced keywords autocomplete in IDE

## Benefits of This Approach

1. **Clear boundaries** - No ambiguity about which options are controlled vs passthrough
2. **Future-proof** - New provider options automatically work without library updates
3. **Composable** - Standard Clojure functions work on providers and options
4. **Validated** - Catch errors at boundaries with helpful messages
5. **Self-documenting** - Schemas serve as documentation

## Notes for Implementation

- Keep the core `prompt` function simple - complexity goes in providers
- Don't try to validate provider options beyond basic types - providers will error on invalid options
- Use `:closed false` in Malli schemas for provider opts to allow unknown keys
- Consider adding a `explain-options` function that uses Malli to show available options
- Provider protocol can have additional methods like `stream?`, `supports-tools?` for capability detection

## Example Error Messages to Provide

```clojure
;; When invalid library option provided
"Invalid option :mylib/sytsem - did you mean :mylib/system?"

;; When unnamespaced key used at top level  
"Unnamespaced key :temperature used. Library options should use :mylib/* namespace, provider options should be under :provider/opts"

;; When validation fails
"Invalid value for :mylib/messages - expected vector of maps with :role and :content"
```

This design provides maximum flexibility while maintaining safety and clarity at the boundaries.

BUT make sure that opts remains optional when calling prompt :)
