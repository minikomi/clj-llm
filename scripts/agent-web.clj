#!/usr/bin/env bb

;; Prefer IPv4 — many hosts don't have working IPv6
(System/setProperty "java.net.preferIPv4Stack" "true")

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backend.openai :as openai]
         '[org.httpkit.server :as hk]
         '[hiccup2.core :as h]
         '[hiccup.util :refer [raw-string]]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[babashka.http-client :as http])

(def port (or (System/getenv "PORT") 8001))

(def openrouter-key (System/getenv "OPENROUTER_KEY"))
(def openai-key (System/getenv "OPENAI_API_KEY"))

(def env-model (System/getenv "LLM_MODEL"))
(def default-openai-model "gpt-4.1-mini")
(def default-openrouter-model "google/gemini-3-flash-preview")

(def data-dir ".chat-data/agent")
(.mkdirs (io/file data-dir))

;; ══════ Provider ══════

(def ai
  (let [settings (if openrouter-key
                   ;; openrouter
                   {:api-key openrouter-key
                    :api-base "https://openrouter.ai/api/v1"
                    :defaults {:model (or env-model default-openrouter-model)}}
                   ;; openai fallback
                   {:defaults {:model (or env-model default-openai-model)}})]
    (openai/backend settings)))

;; ══════ Tool helper ══════

(defn deftool
  "Create a tool fn from a schema and implementation. Returns a fn with
   :malli/schema metadata so clj-llm can discover it automatically."
  [input-schema f]
  (with-meta f {:malli/schema [:=> [:cat input-schema] :any]}))

;; ══════ Search cascade: Kagi → Tavily → DuckDuckGo ══════

(def ^:private kagi-key (System/getenv "KAGI_API_KEY"))
(def ^:private tavily-key (System/getenv "TAVILY_API_KEY"))

(defn- search-kagi [query]
  (let [resp (http/get "https://kagi.com/api/v0/search"
               {:headers {"Authorization" (str "Bot " kagi-key)}
                :query-params {"q" query "limit" "5"}})
        data (json/parse-string (:body resp) true)]
    (when-let [errs (seq (:error data))]
      (throw (ex-info (str "Kagi: " (:msg (first errs))) {})))
    (let [results (filter #(= (:t %) 0) (:data data))]
      (when (seq results)
        (str/join "\n\n"
          (map-indexed (fn [i r]
                         (str (inc i) ". " (:title r)
                              (when-let [s (:snippet r)] (str "\n   " s))
                              "\n   URL: " (:url r)))
                       (take 5 results)))))))

(defn- search-tavily [query]
  (let [resp (http/post "https://api.tavily.com/search"
               {:headers {"Content-Type" "application/json"}
                :body (json/generate-string {:query query :api_key tavily-key
                                             :max_results 5 :include_answer true})})
        data (json/parse-string (:body resp) true)]
    (str (when-let [answer (:answer data)] (str "Summary: " answer "\n\n"))
         (str/join "\n\n"
           (map-indexed (fn [i r]
                          (str (inc i) ". " (:title r)
                               "\n   " (:content r)
                               "\n   URL: " (:url r)))
                        (:results data))))))

(defn- search-ddg [query]
  (let [resp (http/post "https://html.duckduckgo.com/html/"
               {:headers {"User-Agent" "Mozilla/5.0"
                          "Content-Type" "application/x-www-form-urlencoded"}
                :body (str "q=" (java.net.URLEncoder/encode query "UTF-8"))})
        body (:body resp)
        links (re-seq #"class=\"result__a\" href=\"([^\"]*)\"[^>]*>([^<]*)</a>" body)
        snippets (re-seq #"class=\"result__snippet\" href=\"[^\"]*\">(.+?)</a>" body)]
    (when (seq links)
      (str/join "\n\n"
        (map-indexed
          (fn [i [_ url title]]
            (let [snippet (some-> (nth snippets i nil) second (str/replace #"<[^>]+>" "") str/trim)]
              (str (inc i) ". " (str/trim title)
                   (when snippet (str "\n   " snippet))
                   "\n   URL: " url)))
          (take 5 links))))))

(def ^:private search-providers
  (cond-> []
    kagi-key   (conj {:name "Kagi"   :fn search-kagi})
    tavily-key (conj {:name "Tavily" :fn search-tavily})
    true       (conj {:name "DuckDuckGo" :fn search-ddg})))

;; ══════ Tool implementations + schemas ══════

(def ^:private wmo-codes
  {0 "Clear sky" 1 "Mainly clear" 2 "Partly cloudy" 3 "Overcast"
   45 "Fog" 48 "Rime fog" 51 "Light drizzle" 53 "Drizzle" 55 "Dense drizzle"
   61 "Slight rain" 63 "Rain" 65 "Heavy rain" 71 "Slight snow" 73 "Snow"
   75 "Heavy snow" 80 "Slight showers" 81 "Showers" 82 "Violent showers"
   95 "Thunderstorm" 96 "Thunderstorm w/ hail" 99 "Thunderstorm w/ heavy hail"})

(def geocode
  (deftool
    [:map {:name "geocode" :description "Look up latitude and longitude for a city"}
     [:city {:description "City name"} :string]]
    (fn [{:keys [city]}]
      (try
        (let [geo  (-> (http/get (str "https://geocoding-api.open-meteo.com/v1/search?name="
                                      (java.net.URLEncoder/encode city "UTF-8") "&count=1"))
                       :body (json/parse-string true))
              loc  (first (:results geo))]
          (if-not loc
            (str "Could not find city: " city)
            {:name (:name loc) :country (:country loc)
             :latitude (:latitude loc) :longitude (:longitude loc)}))
        (catch Exception e
          (str "Geocoding error: " (.getMessage e)))))))

(def get-weather
  (deftool
    [:map {:name "get_weather" :description "Get current weather at a location. Call geocode first to get coordinates."}
     [:latitude {:description "Latitude"} :double]
     [:longitude {:description "Longitude"} :double]]
    (fn [{:keys [latitude longitude]}]
      (try
        (let [wx (-> (http/get (str "https://api.open-meteo.com/v1/jma?latitude=" latitude
                                    "&longitude=" longitude
                                    "&current=temperature_2m,weather_code,wind_speed_10m"
                                    "&timezone=auto"))
                     :body (json/parse-string true))
              c  (:current wx)]
          (str (get wmo-codes (:weather_code c) "Unknown") ", "
               (:temperature_2m c) "°C, "
               "wind " (:wind_speed_10m c) " km/h"))
        (catch Exception e
          (str "Weather error: " (.getMessage e)))))))

(def search-web
  (deftool
    [:map {:name "search_web" :description "Search the web for information"}
     [:query {:description "Search query"} :string]]
    (fn [{:keys [query]}]
      (loop [[provider & more] search-providers]
        (if-not provider
          (str "No results found for: " query)
          (let [result (try ((:fn provider) query) (catch Exception _ nil))]
            (if (and result (not (str/blank? result)))
              result
              (recur more))))))))

(def wikipedia
  (deftool
    [:map {:name "wikipedia" :description "Look up a Wikipedia article summary. Use for factual info about people, places, concepts, events."}
     [:topic {:description "Article title or topic to look up"} :string]]
    (fn [{:keys [topic]}]
      (try
        (let [encoded (java.net.URLEncoder/encode (str/replace topic " " "_") "UTF-8")
              resp (http/get (str "https://en.wikipedia.org/api/rest_v1/page/summary/" encoded)
                     {:headers {"User-Agent" "clj-llm-agent/1.0"}})
              data (json/parse-string (:body resp) true)]
          (if (:extract data)
            (str (:title data) "\n\n" (:extract data)
                 (when-let [url (get-in data [:content_urls :desktop :page])]
                   (str "\n\nURL: " url)))
            (str "No Wikipedia article found for: " topic)))
        (catch Exception e
          (str "Wikipedia error: " (.getMessage e)))))))

(def fetch-url
  (deftool
    [:map {:name "fetch_url" :description "Fetch a webpage and extract its text content. Use to read articles, docs, or follow links from search results."}
     [:url {:description "URL to fetch"} :string]]
    (fn [{:keys [url]}]
      (try
        (let [resp (http/get url {:headers {"User-Agent" "Mozilla/5.0"}
                                  :throw false
                                  :follow-redirects true})
              body (or (:body resp) "")
              text (-> body
                       (str/replace #"<script[^>]*>.*?</script>" " ")
                       (str/replace #"<style[^>]*>.*?</style>" " ")
                       (str/replace #"<[^>]+>" " ")
                       (str/replace #"&nbsp;" " ")
                       (str/replace #"&amp;" "&")
                       (str/replace #"&lt;" "<")
                       (str/replace #"&gt;" ">")
                       (str/replace #"&#x27;" "'")
                       (str/replace #"&#x2F;" "/")
                       (str/replace #"\s+" " ")
                       str/trim)]
          (if (str/blank? text)
            (str "No readable content at: " url)
            (let [truncated (subs text 0 (min 3000 (count text)))]
              (str truncated
                   (when (> (count text) 3000) "\n\n[truncated]")))))
        (catch Exception e
          (str "Fetch error: " (.getMessage e)))))))

(def exchange-rate
  (deftool
    [:map {:name "exchange_rate" :description "Convert between currencies using live exchange rates."}
     [:from {:description "Source currency code, e.g. USD"} :string]
     [:to {:description "Target currency code, e.g. EUR, JPY. Comma-separated for multiple."} :string]
     [:amount {:description "Amount to convert" :optional true} :double]]
    (fn [{:keys [from to amount]}]
      (try
        (let [amt (or amount 1.0)
              resp (http/get "https://api.frankfurter.dev/v1/latest"
                     {:query-params {"base" (str/upper-case from)
                                     "symbols" (str/upper-case to)}})
              data (json/parse-string (:body resp) true)
              rates (:rates data)]
          (if (empty? rates)
            (str "No rates found for " from " → " to)
            (str/join "\n"
              (map (fn [[currency rate]]
                     (str amt " " (str/upper-case from) " = "
                          (format "%.2f" (* amt (double rate))) " " (name currency)))
                   rates))))
        (catch Exception e
          (str "Exchange rate error: " (.getMessage e)))))))

(def define-word
  (deftool
    [:map {:name "define_word" :description "Look up the definition of an English word."}
     [:word {:description "Word to define"} :string]]
    (fn [{:keys [word]}]
      (try
        (let [resp (http/get (str "https://api.dictionaryapi.dev/api/v2/entries/en/"
                                  (java.net.URLEncoder/encode word "UTF-8")))
              data (first (json/parse-string (:body resp) true))
              phonetic (or (:text (:phonetics (first (:phonetics data)))) (:phonetic data) "")]
          (if-not data
            (str "No definition found for: " word)
            (str (:word data) (when (seq phonetic) (str "  " phonetic)) "\n\n"
                 (str/join "\n\n"
                   (for [meaning (:meanings data)]
                     (str "[" (:partOfSpeech meaning) "]\n"
                          (str/join "\n"
                            (map-indexed
                              (fn [i d]
                                (str (inc i) ". " (:definition d)
                                     (when-let [ex (:example d)] (str "\n   Example: \"" ex "\""))))
                              (take 3 (:definitions meaning))))))))))
        (catch Exception e
          (str "Definition error: " (.getMessage e)))))))

(def hacker-news
  (deftool
    [:map {:name "hacker_news" :description "Get current top stories from Hacker News."}
     [:count {:description "Number of stories (1-15, default 5)" :optional true} :int]]
    (fn [{:keys [count]}]
      (try
        (let [n (min 15 (max 1 (or count 5)))
              ids (take n (json/parse-string
                            (:body (http/get "https://hacker-news.firebaseio.com/v0/topstories.json")) true))
              stories (pmap (fn [id]
                              (json/parse-string
                                (:body (http/get (str "https://hacker-news.firebaseio.com/v0/item/" id ".json"))) true))
                            ids)]
          (str/join "\n\n"
            (map-indexed
              (fn [i s]
                (str (inc i) ". " (:title s)
                     (when-let [u (:url s)] (str "\n   URL: " u))
                     "\n   " (:score s) " points, " (:descendants s 0) " comments"))
              stories)))
        (catch Exception e
          (str "Hacker News error: " (.getMessage e)))))))

(def render-canvas
  (deftool
    [:map {:name "render_canvas"
           :description "Draw a visualization using HTML5 Canvas. Write JavaScript that uses `ctx` (a 2D context) and `w`/`h` (width/height). Good for charts, graphs, diagrams, pixel art, illustrations. Set background with ctx.fillRect(0,0,w,h) first. Use colors, shapes, text, arcs, etc."}
     [:code {:description "JavaScript code using ctx (CanvasRenderingContext2D), w (width), h (height). Example: ctx.fillStyle='#1a1a2e'; ctx.fillRect(0,0,w,h); ctx.fillStyle='#e74c3c'; ctx.beginPath(); ctx.arc(w/2,h/2,50,0,Math.PI*2); ctx.fill();"} :string]
     [:title {:description "Short title for the image" :optional true} :string]
     [:width {:description "Canvas width in pixels (default 600)" :optional true} :int]
     [:height {:description "Canvas height in pixels (default 400)" :optional true} :int]]
    (fn [{:keys [code title width height]}]
      ;; Return string for LLM history; the on-tool-result callback
      ;; detects render_canvas by tool name and renders the canvas HTML.
      (str "Canvas image rendered and displayed to user."
           "::canvas::" (json/generate-string {:code code :title title
                                                :width (or width 600)
                                                :height (or height 400)})))))

(def calculate
  (deftool
    [:map {:name "calculate" :description "Evaluate a Clojure math expression. Use prefix notation, e.g. (+ 2 2), (Math/sqrt 144), (Math/pow 2 10)"}
     [:expression {:description "Clojure expression, e.g. (+ (Math/pow 2 10) (Math/sqrt 256))"} :string]]
    (fn [{:keys [expression]}]
      (str "Result: "
           (try (load-string expression)
                (catch Exception e (str "Error: " (.getMessage e))))))))

(def agent-tools [geocode get-weather search-web wikipedia fetch-url
                  exchange-rate define-word hacker-news render-canvas calculate])

(def canvas-counter (atom 0))

(defn canvas-html
  "Render an inline canvas element with JS code."
  [{:keys [code title width height]}]
  (let [id (str "canvas-" (swap! canvas-counter inc))
        w (or width 600)
        h (or height 400)]
    (str "<div class='canvas-wrap'>"
         (when title (str "<div class='canvas-title'>" title "</div>"))
         "<canvas id='" id "' width='" w "' height='" h "'"
         " style='max-width:100%;border-radius:8px;'></canvas>"
         "<script>(function(){var c=document.getElementById('" id "');"
         "var ctx=c.getContext('2d');var w=" w ",h=" h ";"
         "try{" code "}catch(e){ctx.fillStyle='#ff4444';ctx.font='14px monospace';"
         "ctx.fillText('Error: '+e.message,10,30);}})();</script>"
         "</div>")))

;; ══════ Chat persistence ══════

(defn chat-path [id] (str data-dir "/" id ".edn"))

(defn save-chat [chat]
  (spit (chat-path (:id chat)) (pr-str chat)))

(defn load-chat [id]
  (let [f (io/file (chat-path id))]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn list-chats []
  (->> (.listFiles (io/file data-dir))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (map #(edn/read-string (slurp %)))
       (sort-by :updated-at)
       reverse))

(defn delete-chat [id]
  (let [f (io/file (chat-path id))] (when (.exists f) (.delete f))))

(defn new-chat-id [] (str (java.util.UUID/randomUUID)))

;; ══════ HTML helpers ══════

(defn render-tool-result
  "Render a single tool result — canvas gets inline HTML, others plain text."
  [result]
  (if (and (map? result) (= :canvas (:type result)))
    (raw-string (canvas-html result))
    (str "→ " result)))

(defn render-message [{:keys [role content tool-steps]}]
  (case (keyword role)
    :user
    (str (h/html [:div.msg.user [:div.bubble content]]))
    :assistant
    (str (h/html
      [:div.msg.assistant
       (when (seq tool-steps)
         [:div.tool-steps
          (for [{:keys [calls results]} tool-steps]
            [:div.tool-step
             (for [[call result] (map vector calls results)]
               (if (and (map? result) (= :canvas (:type result)))
                 [:div.tool-use
                  [:div.tool-call "🛠️ " [:strong (:name call)] " "
                   (or (:title result) "canvas")]
                  (raw-string (canvas-html result))]
                 [:div.tool-use
                  [:div.tool-call "🛠️ " [:strong (:name call)] " " (pr-str (:arguments call))]
                  [:div.tool-result "→ " result]]))])])
       [:div.bubble content]]))
    ""))

(defn render-sidebar [chats active-id]
  (str (h/html
    [:div.sidebar-hdr [:h2 "Agent"] [:button.sidebar-close {:onclick "closeSidebar()"} "×"]]
    [:a.new-chat {:href "/"} "+ New chat"]
    [:div.chat-list
     (for [{:keys [id title]} chats]
       [:div.chat-item {:class (when (= id active-id) "active")}
        [:a {:href (str "/c/" id)} (or title "New chat")]
        [:button.del {:onclick (str "fetch('/c/" id "/delete',{method:'POST'}).then(()=>location.href='/')")} "×"]])])))

;; ══════ CSS ══════

(def css "
* { box-sizing:border-box; margin:0; padding:0 }
body { font-family:-apple-system,system-ui,sans-serif; background:#0a0a0a; color:#e0e0e0; height:100vh; display:flex }
a { color:#8ab4f8; text-decoration:none }
.sidebar { width:260px; background:#111; border-right:1px solid #222; display:flex; flex-direction:column; height:100vh; overflow-y:auto }
.sidebar-hdr { padding:16px; display:flex; justify-content:space-between; align-items:center }
.sidebar-hdr h2 { font-size:16px; color:#aaa }
.sidebar-close { display:none; background:none; border:none; color:#888; font-size:20px; cursor:pointer }
.new-chat { display:block; margin:8px 12px; padding:10px; background:#1a1a2e; border:1px solid #333; border-radius:8px; text-align:center; font-size:14px }
.new-chat:hover { background:#222240 }
.chat-list { flex:1 }
.chat-item { display:flex; align-items:center; padding:4px 12px }
.chat-item a { flex:1; padding:8px; border-radius:6px; font-size:13px; color:#ccc; overflow:hidden; white-space:nowrap; text-overflow:ellipsis }
.chat-item.active a { background:#1a1a2e; color:#fff }
.chat-item:hover a { background:#1a1a2e }
.chat-item .del { background:none; border:none; color:#555; cursor:pointer; font-size:14px; padding:4px 8px; opacity:0 }
.chat-item:hover .del { opacity:1 }
.main { flex:1; display:flex; flex-direction:column; height:100vh }
.messages { flex:1; overflow-y:auto; padding:20px; max-width:800px; margin:0 auto; width:100% }
.msg { margin:8px 0; display:flex }
.msg.user { justify-content:flex-end }
.msg.user .bubble { background:#1a3a5c; border-radius:16px 16px 4px 16px; padding:10px 16px; max-width:70%; white-space:pre-wrap }
.msg.assistant { flex-direction:column }
.msg.assistant .bubble { background:#1a1a2e; border-radius:16px 16px 16px 4px; padding:10px 16px; max-width:85%; white-space:pre-wrap }
.tool-steps { margin-bottom:6px }
.tool-step { margin:4px 0 }
.tool-use { background:#111; border:1px solid #2a2a3a; border-radius:8px; padding:8px 12px; margin:2px 0; font-size:12px; font-family:monospace }
.tool-call { color:#b8d4f0 }
.tool-result { color:#7a9; margin-top:2px }
.canvas-wrap { margin:6px 0; }
.canvas-wrap canvas { display:block; background:#0d0d1a; }
.canvas-title { font-size:12px; color:#8ab4f8; margin-bottom:4px; font-weight:500 }
.input-row { display:flex; padding:12px 20px; border-top:1px solid #222; max-width:800px; margin:0 auto; width:100%; gap:8px }
.input-row textarea { flex:1; background:#111; color:#e0e0e0; border:1px solid #333; border-radius:8px; padding:10px 14px; font-size:15px; resize:none; font-family:inherit; min-height:44px }
.input-row textarea:focus { outline:none; border-color:#555 }
.input-row button { background:#2563eb; color:white; border:none; border-radius:8px; padding:10px 20px; font-size:15px; cursor:pointer }
.input-row button:hover { background:#1d4ed8 }
.input-row button:disabled { background:#333; cursor:not-allowed }
.thinking { color:#888; font-style:italic; padding:8px 16px; font-size:13px }
.hamburger { display:none; position:fixed; top:10px; left:10px; z-index:100; background:#222; border:1px solid #444; color:#e0e0e0; font-size:20px; padding:6px 10px; border-radius:6px; cursor:pointer }
.overlay { display:none; position:fixed; inset:0; background:rgba(0,0,0,0.6); z-index:49 }
@media(max-width:768px) {
  .hamburger { display:block }
  .sidebar { position:fixed; left:-270px; z-index:50; transition:left .2s }
  .sidebar.open { left:0 }
  .sidebar-close { display:block }
  .overlay.open { display:block }
  .input-row textarea { font-size:16px }
}
")

;; ══════ Client JS ══════

(def client-js (str
  "function send(){"
  "var ta=document.getElementById('msg');var msg=ta.value.trim();"
  "if(!msg)return;ta.value='';ta.disabled=true;"
  "document.getElementById('send-btn').disabled=true;"
  "var form=document.getElementById('form');var url=form.action;"
  "fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
  "body:'message='+encodeURIComponent(msg)})"
  ".then(function(r){return r.json()})"
  ".then(function(d){"
  "  document.getElementById('sidebar').innerHTML=d.sidebarHtml;"
  "  var msgs=document.getElementById('messages');"
  "  msgs.insertAdjacentHTML('beforeend',d.userHtml);"
  "  msgs.insertAdjacentHTML('beforeend','<div class=\"thinking\" id=\"status\">Thinking...</div>');"
  "  scrollDown();"
  "  var es=new EventSource('/c/'+d.chatId+'/stream');"
  "  var buf='';"
  "  es.addEventListener('step',function(e){"
  "    var s=document.getElementById('status');"
  "    if(s){s.innerHTML=e.data;runCanvasScripts(s);}"
  "  });"
  "  es.addEventListener('chunk',function(e){"
  "    var s=document.getElementById('status');if(s)s.remove();"
  "    buf+=e.data.replace(/\\n/g,'\\n');"
  "    var el=document.getElementById('assistant-streaming');"
  "    if(!el){msgs.insertAdjacentHTML('beforeend','<div class=\"msg assistant\"><div class=\"bubble\" id=\"assistant-streaming\"></div></div>');el=document.getElementById('assistant-streaming');}"
  "    el.textContent=buf;scrollDown();"
  "  });"
  "  es.addEventListener('done',function(e){"
  "    es.close();"
  "    var s=document.getElementById('status');if(s)s.remove();"
  "    ta.disabled=false;document.getElementById('send-btn').disabled=false;ta.focus();"
  "    var d2=JSON.parse(e.data);"
  "    msgs.innerHTML=d2.messagesHtml;runCanvasScripts(msgs);scrollDown();"
  "    if(window.history.replaceState)window.history.replaceState(null,null,'/c/'+d2.chatId);"
  "    document.getElementById('form').action='/c/'+d2.chatId+'/send';"
  "  });"
  "  es.onerror=function(){es.close();ta.disabled=false;document.getElementById('send-btn').disabled=false;};"
  "})}"
  "function runCanvasScripts(el){var scripts=el.querySelectorAll('.canvas-wrap script');"
  "scripts.forEach(function(s){var ns=document.createElement('script');ns.textContent=s.textContent;s.parentNode.replaceChild(ns,s);});}\n"
  "function scrollDown(){var el=document.getElementById('messages');el.scrollTop=el.scrollHeight;}"
  "function toggleSidebar(){document.getElementById('sidebar').classList.toggle('open');document.getElementById('overlay').classList.toggle('open');}"
  "function closeSidebar(){document.getElementById('sidebar').classList.remove('open');document.getElementById('overlay').classList.remove('open');}"
  ))

;; ══════ Page ══════

(defn page [chat-id]
  (let [chat (when chat-id (load-chat chat-id))
        messages (or (:messages chat) [])]
    (str (h/html
      (raw-string "<!DOCTYPE html>")
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title "clj-llm agent"]
        [:style (raw-string css)]]
       [:body
        [:button.hamburger {:onclick "toggleSidebar()"} "☰"]
        [:div#overlay.overlay {:onclick "closeSidebar()"}]
        [:div#sidebar.sidebar (raw-string (render-sidebar (list-chats) chat-id))]
        [:div.main
         [:div#messages.messages
          (if (empty? messages)
            [:div {:style "text-align:center;color:#555;margin-top:40vh"}
             [:p {:style "font-size:18px"} "🤖 Agent with tools"]
             [:p {:style "font-size:13px;margin-top:8px;color:#444"}
              "Weather · Search · Wikipedia · Read URLs · Currency · Dictionary · Hacker News · Canvas · Math"]]
            (raw-string (apply str (map render-message messages))))]
         [:form#form.input-row {:method "POST" :action (str "/c/" (or chat-id "new") "/send")}
          [:textarea#msg {:name "message" :placeholder "Ask me anything..." :rows 1 :autofocus true
                          :onkeydown "if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();send()}"}]
          [:button#send-btn {:type "button" :onclick "send()"} "Send"]]
         [:script (raw-string client-js)]
         [:script (raw-string "runCanvasScripts(document.getElementById('messages'));")]]]]))))

;; ══════ Pending responses (for SSE) ══════

(def pending (atom {}))

(defn sse-event [event-name data]
  (str "event: " event-name "\ndata: " data "\n\n"))

;; ══════ Handler ══════

(defn parse-form [body]
  (when body
    (let [s (if (string? body) body (slurp body))]
      (->> (str/split s #"&")
           (map #(str/split % #"=" 2))
           (map (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")]))
           (into {})))))

(defn handler [{:keys [uri request-method body] :as req}]
  (cond
    ;; Home
    (and (= uri "/") (= request-method :get))
    {:status 200 :headers {"Content-Type" "text/html"} :body (page nil)}

    ;; View chat
    (and (re-matches #"/c/[^/]+" uri) (= request-method :get))
    (let [chat-id (subs uri 3)]
      {:status 200 :headers {"Content-Type" "text/html"} :body (page chat-id)})

    ;; Delete chat
    (and (re-matches #"/c/[^/]+/delete" uri) (= request-method :post))
    (let [chat-id (subs uri 3 (- (count uri) 7))]
      (delete-chat chat-id)
      {:status 200 :headers {"Content-Type" "application/json"} :body "{}"})

    ;; Send message
    (and (re-matches #"/c/[^/]+/send" uri) (= request-method :post))
    (let [chat-id-raw (subs uri 3 (- (count uri) 5))
          chat-id (if (= chat-id-raw "new") (new-chat-id) chat-id-raw)
          {:keys [message]} (parse-form body)
          chat (or (load-chat chat-id) {:id chat-id :messages [] :llm-history [] :created-at (System/currentTimeMillis)})
          user-msg {:role :user :content message}
          chat (-> chat
                   (update :messages conj user-msg)
                   (update :llm-history conj {:role "user" :content message})
                   (assoc :updated-at (System/currentTimeMillis)))
          chat (if (:title chat) chat (assoc chat :title (subs message 0 (min 50 (count message)))))]
      (save-chat chat)
      (swap! pending assoc chat-id {:chat chat :message message})
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
               {:chatId chat-id
                :userHtml (render-message user-msg)
                :sidebarHtml (render-sidebar (list-chats) chat-id)})})

    ;; SSE stream — run the agent loop
    (and (re-matches #"/c/[^/]+/stream" uri) (= request-method :get))
    (let [chat-id (subs uri 3 (- (count uri) 7))
          {:keys [chat]} (get @pending chat-id)]
      (swap! pending dissoc chat-id)
      (if-not chat
        {:status 404 :body "no pending"}
        (hk/as-channel req
          {:on-open
           (fn [ch]
             (hk/send! ch {:status 200
                           :headers {"Content-Type" "text/event-stream"
                                     "Cache-Control" "no-cache"
                                     "X-Accel-Buffering" "no"}} false)
             (future
               (try
                 (let [step-displays (atom [])
                       result (llm/run-agent ai agent-tools
                                {:max-steps 5
                                 :system-prompt "You are a helpful assistant with tools. Always use tools to answer questions — never guess or say you cannot access data. Use geocode before get_weather. Use search_web for general queries. Use wikipedia for factual info about people, places, concepts. Use fetch_url to read full articles. Use exchange_rate for currency questions. Use define_word for vocabulary. Use hacker_news for tech news. Use render_canvas to draw charts, graphs, diagrams, or any visual. When rendering canvas, always set a dark background first (e.g. ctx.fillStyle='#1a1a2e'; ctx.fillRect(0,0,w,h)). Use vibrant colors. Chain tools when needed."
                                 :on-tool-calls
                                 (fn [{:keys [tool-calls]}]
                                   (swap! step-displays conj {:calls tool-calls :results []}))
                                 :on-tool-result
                                 (fn [{:keys [tool-call result]}]
                                   (let [r (str (or result ""))
                                         canvas? (str/includes? r "::canvas::")
                                         canvas-data (when canvas?
                                                       (assoc (json/parse-string (subs r (+ (str/index-of r "::canvas::") 10)) true)
                                                              :type :canvas))
                                         display-r (if canvas? canvas-data r)]
                                     (swap! step-displays update (dec (count @step-displays))
                                            update :results conj display-r)
                                     (let [step-html
                                           (str "<div class='tool-use'>"
                                                (if canvas?
                                                  (str "<div class='tool-call'>🛠️ <strong>" (:name tool-call) "</strong> "
                                                       (or (:title canvas-data) "canvas") "</div>"
                                                       (canvas-html canvas-data))
                                                  (str "<div class='tool-call'>🛠️ <strong>" (:name tool-call) "</strong> "
                                                       (pr-str (:arguments tool-call)) "</div>"
                                                       "<div class='tool-result'>→ " r "</div>"))
                                                "</div>")]
                                       (hk/send! ch (sse-event "step" step-html) false))))
                                 :on-text
                                 (fn [chunk]
                                   (hk/send! ch (sse-event "chunk" chunk) false))}
                                (vec (:llm-history chat)))
                       text (or (:text result) "")
                       assistant-msg {:role :assistant :content text :tool-steps @step-displays}
                       final-chat (-> chat
                                      (update :messages conj assistant-msg)
                                      (assoc :llm-history (:history result))
                                      (assoc :updated-at (System/currentTimeMillis)))
                       _ (save-chat final-chat)
                       all-html (apply str (map render-message (:messages final-chat)))]
                   (hk/send! ch (sse-event "done"
                                  (json/generate-string {:chatId chat-id :messagesHtml all-html})) false)
                   (hk/close ch))
                 (catch Exception e
                   (println "Agent error:" (.getMessage e))
                   (try
                     (hk/send! ch (sse-event "chunk" (str "Error: " (.getMessage e))) false)
                     (hk/close ch)
                     (catch Exception _))))))})))

    :else {:status 404 :body "not found"}))

;; ══════ Start ══════

(println (str "🤖 Agent on http://localhost:" port))
(hk/run-server handler {:port port})
@(promise)
