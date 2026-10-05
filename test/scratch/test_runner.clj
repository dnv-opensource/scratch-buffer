(ns scratch.test-runner
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing run-tests]]
            [scratch.calendar :as calendar]
            [scratch.commands :as commands]
            [scratch.constellation :as constellation]
            [scratch.content :as content]
            [scratch.fixtures :as fixtures]
            [scratch.github :as github]
            [scratch.html :as html]
            [scratch.meetings :as meetings]
            [scratch.parentheses :as parentheses]
            [scratch.reading :as reading]
            [scratch.source-maps :as source-maps]
            [scratch.router :as router]
            [scratch.state :as state]
            [scratch.view :as view]))

(def data (content/load-data "."))
(def example-meeting (first (:meetings fixtures/community)))
(def fixture-data (fixtures/with-community data))
(def start (state/initial data "/scratch-buffer/" "/"))
(def fixture-start (state/initial fixture-data "/scratch-buffer/" "/"))
(defn step [s action] (:state (state/transition s action)))

(def discussion-snapshot
  (let [repository (get-in data [:site :repository])
        date "2026-10-05T14:00:00Z"
        url (str repository "/discussions/42")
        comment {:url (str url "#discussioncomment-1") :body "A reply, not HTML."
                 :created-at date :author nil :replies []}]
    {:version 1 :repository repository :generated-at date :status "ready"
     :discussions [{:number 42 :title "<script>Not a script</script>" :url url :body "<img src=x onerror=alert(1)>"
                    :created-at date :updated-at date :author {:login "example-member" :url "https://github.com/example-member"}
                    :comments [comment]}]}))

(deftest discussion-snapshots
  (let [repository (get-in data [:site :repository])
        loaded (state/transition start [:github/load-snapshot])
        pending (:state loaded)
        ready (step pending [:github/snapshot-loaded 1 discussion-snapshot])
        linked (-> ready
                   (assoc-in [:content :snippets 0 :discussion-number] 42)
                   (step [:buffer/open (str "/snippets/" (get-in data [:snippets 0 :id]) "/")]))
        output (html/render (view/discussion-section linked))]
    (is (= discussion-snapshot (github/validate-snapshot! repository discussion-snapshot)))
    (is (= :loading (get-in pending [:github :status])))
    (is (= [[:github/fetch-snapshot "/scratch-buffer/data/generated/discussions.json" 1]] (:effects loaded)))
    (is (empty? (:effects (state/transition pending [:github/load-snapshot]))))
    (is (= :ready (get-in ready [:github :status])))
    (is (empty? (:effects (state/transition ready [:github/load-snapshot]))))
    (is (= pending (step pending [:github/snapshot-loaded 0 discussion-snapshot])))
    (is (= ready (step ready [:github/snapshot-failed 0 "Old error"])))
    (is (= :error (get-in (step pending [:github/snapshot-failed 1 "Could not load snapshot"]) [:github :status])))
    (is (= 2 (get-in (:state (state/transition ready [:github/load-snapshot true])) [:github :revision])))
    (is (empty? (:effects (state/transition (step start [:buffer/open "/help/"]) [:github/load-snapshot]))))
    (is (= 42 (github/discussion-number linked)))
    (is (= (str repository "/discussions/42") (github/discussion-url linked :page)))
    (is (str/includes? output "&lt;script&gt;Not a script"))
    (is (str/includes? output "&lt;img src=x"))
    (is (not (str/includes? output "<script>")))
    (is (str/includes? output "Updated"))
    (is (str/includes? output "UTC"))
    (is (str/includes? output "A reply, not HTML."))
    (is (not (str/includes? (content/visible-text (view/discussion-section linked)) "A reply, not HTML.")))
    (is (= (str repository "/discussions/42")
           (github/discussion-url (assoc-in start [:content :pages 1 :discussion-number] 42) :page)))
    (doseq [status ["disabled" "unavailable"]]
      (is (map? (github/validate-snapshot! repository
                                          (assoc discussion-snapshot :status status :discussions []
                                                 :generated-at (when (= status "disabled") (:generated-at discussion-snapshot)))))))
    (doseq [snapshot [(assoc discussion-snapshot :repository "https://github.com/elsewhere/other")
                      (assoc discussion-snapshot :version 2)
                      (assoc discussion-snapshot :token "secret")
                      (assoc discussion-snapshot :generated-at "2026-99-99")
                      (assoc discussion-snapshot :status "unavailable")
                      (update discussion-snapshot :discussions #(into % %))
                      (assoc-in discussion-snapshot [:discussions 0 :url] "javascript:alert(1)")
                      (assoc-in discussion-snapshot [:discussions 0 :author :url] "https://github.com.evil.org/person")
                      (assoc-in discussion-snapshot [:discussions 0 :author] false)
                      (assoc-in discussion-snapshot [:discussions 0 :comments 0 :url] "https://example.org/comment")]]
      (is (thrown? Exception (github/validate-snapshot! repository snapshot))))))

(deftest routing
  (is (= "/scratch-buffer/" (router/normalize-base "scratch-buffer")))
  (is (= "/" (router/normalize-base nil)))
  (is (= "/talks/" (router/local-path "/scratch-buffer/" "/scratch-buffer/talks")))
  (is (nil? (router/local-path "/scratch-buffer/" "/scratch-buffer-else/")))
  (is (= :scratch (:id (router/resolve-route data "/"))))
  (is (= :talk (:id (router/resolve-route fixture-data "/meetings/talks/a-smaller-init/index.html"))))
  (is (= :not-found (:id (router/resolve-route data "/talks/a-smaller-init/"))))
  (is (= "/people/#join" (router/destination "/join/")))
  (is (= "/meetings/#talks" (router/destination "/talks/")))
  (is (= "/snippets/eglot/" (router/destination "/packages/eglot/")))
  (is (= "/people/#join" (router/destination "/people/#join")))
  (doseq [[legacy target] router/redirects]
    (is (= (router/resolve-route data target) (router/resolve-route data legacy))))
  (is (= :person (:id (router/resolve-route fixture-data "/people/example-member/"))))
  (is (= :not-found (:id (router/resolve-route data "/missing/"))))
  (doseq [route (router/routes data)]
    (is (= route (router/resolve-route data (:path route))))))

(deftest command-completion
  (is (= :meetings (:id (first (commands/match-commands "mee" commands/registry)))))
  (is (= :meetings (:id (first (commands/match-commands "mtgs" commands/registry)))))
  (is (= :toggle-theme (:id (first (commands/match-commands "TOGGLE" commands/registry)))))
  (is (empty? (commands/match-commands "flibbertigibbet" commands/registry)))
  (is (= (count commands/registry) (count (commands/match-commands " " commands/registry))))
  (is (= 3 (count (commands/candidates (step start [:minibuffer/open :themes])))))
  (is (= (count (router/routes data)) (count (commands/candidates (step start [:minibuffer/open :buffers]))))))

(deftest teams-chat
  (let [url (get-in data [:site :teams-url])
        candidate (first (commands/match-commands "teams" commands/registry))
        executed (state/transition start [:command/execute candidate])
        opened (state/transition start [:teams/open])
        offline (state/transition (step start [:pwa/offline true]) [:teams/open])]
    (is (= :teams (:id candidate)))
    (is (= [:dispatch [:teams/open]] (last (:effects executed))))
    (is (= [[:external/open-tab url]] (:effects opened)))
    (is (empty? (:effects offline)))
    (is (str/includes? (get-in offline [:state :ui :notice]) "Teams chat needs a connection"))
    (doseq [path ["/" "/about/" "/people/"]]
      (let [output (html/render (view/view (step start [:buffer/open path])))]
        (is (str/includes? output (str "href=\"" (html/escape-text url) "\"")))
        (is (str/includes? output "target=\"_blank\""))
        (is (str/includes? output "rel=\"noopener noreferrer\""))
        (is (str/includes? output "opens in a new tab"))
        (is (str/includes? output "Teams chat")))))
  (let [read-record content/read-record]
    (doseq [url [nil "javascript:alert(1)" "http://teams.microsoft.com/chat"
                 "https://teams.microsoft.com.example.org/chat" "https://example.org/chat"]]
      (with-redefs [content/read-record (fn [path]
                                         (let [record (read-record path)]
                                           (if (= "site.edn" (str (fs/file-name path)))
                                             (assoc record :teams-url url)
                                             record)))]
        (is (thrown-with-msg? Exception #"Teams chat" (content/load-data ".")))))))

(deftest hero-variants
  (is (= :constellation (get-in start [:ui :hero])))
  (let [changed (state/transition start [:hero/select :parentheses])
        state (:state changed)
        output (html/render (view/hero-illustration state))]
    (is (= :parentheses (get-in state [:ui :hero])))
    (is (= (:route start) (:route state)))
    (is (= [[:constellation/sync]] (:effects changed)))
    (is (= :parentheses (get-in (-> state (step [:buffer/open "/about/"]) (step [:buffer/open "/"])) [:ui :hero])))
    (is (= 12 (count (re-seq #"class=\"orbital-beam\"" output))))
    (is (= 12 (count (re-seq #"class=\"beam-tail\"" output))))
    (is (= 12 (count (re-seq #"<linearGradient " output))))
    (is (= 60 (count (re-seq #"<stop " output))))
    (is (str/includes? output "class=\"orbit-word-current\""))
    (is (str/includes? output "tramp"))
    (is (str/includes? output "aria-hidden=\"true\""))
    (is (not (str/includes? (content/visible-text (view/buffer-view state)) "tramp")))
    (is (not (str/includes? output "constellation-current")))
    (is (not (str/includes? output "<button"))))
  (is (thrown? Exception (state/transition start [:hero/select :unknown])))
  (is (= 12 (count (set (map :duration parentheses/beams)))))
  (is (= [0 0] (first parentheses/tail-stops)))
  (is (= [1 1] (last parentheses/tail-stops)))
  (is (apply < (map first parentheses/tail-stops)))
  (is (apply < (map second parentheses/tail-stops)))
  (doseq [{:keys [radius duration direction turn phase tail-deg] :as beam} parentheses/beams]
    (is (<= 100 radius 240))
    (is (<= 5800 duration 15000))
    (is (#{1 -1} direction))
    (is (< 0 tail-deg 180))
    (is (str/includes? (parentheses/plane beam) (str "rotate(" turn ")")))
    (is (str/ends-with? (parentheses/tail-path beam) (str " " radius " 0")))
    (let [[x y] (parentheses/tail-start beam)
          frames (parentheses/frames beam)
          angles (map #(parentheses/angle beam (:offset %)) frames)
          steps (map #(* direction (- %2 %1)) angles (next angles))]
      (is (< (abs (- (* radius radius) (+ (* x x) (* y y)))) 0.000001))
      (is (= (neg? y) (pos? direction)))
      (is (= 49 (count frames)))
      (is (= 0 (:offset (first frames))))
      (is (= 1 (:offset (last frames))))
      (is (every? pos? steps))
      (is (> (/ (apply max steps) (apply min steps)) 1.5))
      (is (every? #(<= 0.38 (:opacity %) 0.93) frames))
      (is (< (abs (- phase (first angles))) 0.000001))
      (is (< (abs (- (+ phase (* direction 360)) (last angles))) 0.000001)))))

(deftest orbital-word-cycling
  (let [names (get-in data [:constellation :names])
        cycle (fn [model]
                (let [advanced (parentheses/advance-word model names)]
                  (parentheses/commit-word advanced (:revision advanced))))
        models (take (inc (count names)) (iterate cycle parentheses/initial-word))]
    (is (= (range (count names)) (map :index (butlast models))))
    (is (= 0 (:index (last models))))
    (is (= (set names) (set (map #(get names (:index %)) models))))
    (is (every? #(nil? (:incoming %)) models))
    (is (thrown? Exception (parentheses/advance-word parentheses/initial-word [])))
    (let [advanced (parentheses/advance-word parentheses/initial-word names)]
      (is (thrown? Exception (parentheses/advance-word advanced names)))
      (is (= advanced (parentheses/commit-word advanced 2)))
      (is (= (cycle parentheses/initial-word) (parentheses/commit-word advanced 1)))
      (is (thrown? Exception (parentheses/commit-word advanced 0)))))
  (let [advanced (state/transition start [:orbit-word/advance])]
    (is (= [[:orbit-word/fade 1]] (:effects advanced)))
    (is (= 1 (get-in advanced [:state :ui :orbit-word :incoming])))
    (is (= 1 (get-in (step (:state advanced) [:orbit-word/commit 1]) [:ui :orbit-word :index])))
    (is (= (:state advanced) (step (:state advanced) [:orbit-word/commit 99])))))

(deftest search-index
  (is (some #(= "/snippets/eglot/" (:path %)) (commands/search (:search data) "language server")))
  (is (some #(= "/people/example-member/" (:path %)) (commands/search (:search fixture-data) "org-mode")))
  (is (some #(= "/people/example-member/" (:path %)) (commands/search (:search fixture-data) "Emacs since 2018")))
  (is (some #(= "/about/" (:path %)) (commands/search (:search data) "beginners")))
  (is (some #(= "/people/" (:path %)) (commands/search (:search data) "once merged")))
  (is (= 1 (count (filter #(= "/snippets/eglot/" (:path %)) (:search data)))))
  (is (not-any? #(contains? router/redirects (:path %)) (:search data)))
  (doseq [id ["which-key" "icomplete-vertical" "savehist" "flymake" "eldoc" "magit"]]
    (is (some #(= (str "/snippets/" id "/") (:path %)) (commands/search (:search data) id))))
  (is (empty? (commands/search (:search data) "")))
  (is (empty? (commands/search (:search data) "nonexistent-toaster")))
  (doseq [entry (:search data)]
    (is (not= :not-found (:id (router/resolve-route data (:path entry)))))))

(deftest transitions
  (let [opened (step start [:buffer/open "/meetings/" {:scroll 100}])
        back (step opened [:buffer/open "/" {:restore? true :pop? true :scroll 200}])
        result (state/transition opened [:buffer/open "/" {:restore? true :pop? true :scroll 200}])]
    (is (= "*meetings*" (get-in opened [:buffer :current])))
    (is (= "/" (get-in opened [:buffer :previous])))
    (is (= 100 (get-in opened [:navigation :positions "/"])))
    (is (= "*scratch-buffer*" (get-in back [:buffer :current])))
    (is (= [:navigation/settle 100 nil] (last (:effects result))))
    (is (= :dispatch (ffirst (:effects (state/transition opened [:buffer/previous]))))))
  (let [result (state/transition start [:buffer/open "/join/"])]
    (is (= :people (get-in result [:state :route :id])))
    (is (= [:history/navigate "/scratch-buffer/people/#join" nil] (first (:effects result))))
    (is (= [:navigation/settle 0 "join"] (last (:effects result)))))
  (let [s (step start [:minibuffer/open :commands])
        down (step s [:minibuffer/move -1])
        cancel (step down [:minibuffer/cancel])]
    (is (= (dec (count commands/registry)) (get-in down [:minibuffer :selection])))
    (is (false? (get-in cancel [:minibuffer :open?]))))
  (let [s (-> start (step [:minibuffer/open :commands]) (step [:command/execute :meetings]))
        history (-> s (step [:minibuffer/open :commands])
                    (step [:minibuffer/input "draft"]) (step [:minibuffer/history 1]))
        draft (step history [:minibuffer/history -1])]
    (is (= ["meetings"] (get-in s [:minibuffer :history])))
    (is (= "meetings" (get-in history [:minibuffer :query])))
    (is (= "draft" (get-in draft [:minibuffer :query]))))
  (doseq [command commands/registry]
    (is (= [:dispatch (:action command)] (last (:effects (state/transition start [:command/execute (:id command)]))))))
  (is (thrown? Exception (state/transition start [:command/execute :missing])))
  (is (thrown? Exception (state/transition start [:unknown]))))

(deftest themes-and-network
  (is (= :light (state/resolved-theme start)))
  (is (= :dark (state/resolved-theme (step start [:theme/system true]))))
  (is (= :light (state/resolved-theme (-> start (step [:theme/system true]) (step [:theme/set :light])))))
  (is (= :system (get-in (step start [:theme/restore :garbage]) [:theme :mode])))
  (is (thrown? Exception (state/transition start [:theme/set :garbage])))
  (let [offline (step start [:pwa/offline true])
        result (state/transition offline [:github/open :page])]
    (is (str/includes? (get-in (:state result) [:ui :notice]) "offline"))
    (is (empty? (:effects result))))
  (is (= "https://github.com/dnv-opensource/scratch-buffer/discussions" (github/discussion-url start :page)))
  (let [with-discussion (assoc-in start [:content :snippets 0 :discussion-number] 123)
        s (step with-discussion [:buffer/open (str "/snippets/" (get-in with-discussion [:content :snippets 0 :id]) "/")])]
    (is (str/ends-with? (github/discussion-url s :page) "/123"))))

(deftest validation
  (with-redefs [slurp (constantly "{:github \"valid\" :joined \"2026-10\"}")]
    (is (= {:github "valid" :joined "2026-10"} (content/read-record "memory.edn"))))
  (doseq [source ["{:id \"one\"} {:id \"two\"}" "{:id \"broken\"" "#unsafe \"x\"" "[]"]]
    (with-redefs [slurp (constantly source)]
      (is (thrown? Exception (content/read-record "memory.edn")))))
  (is (= "example-member" (:github (content/validate-member! {:github "example-member" :joined "2026-10"} "example-member.edn"))))
  (doseq [year ["1976" "2018" (subs (:today data) 0 4)]]
    (is (= year (:emacs-since (content/validate-member! {:github "valid" :joined "2026-10" :emacs-since year} "valid.edn")))))
  (doseq [year [nil "" "1975" "9999" "2018-10" "18" "unknown" 2018]]
    (is (thrown? Exception (content/validate-member! {:github "valid" :joined "2026-10" :emacs-since year} "valid.edn"))))
  (is (= "A Public Name" (:full-name (content/validate-member! {:github "valid" :joined "2026-10" :full-name "A Public Name"} "valid.edn"))))
  (doseq [name [nil "" "   " 42 []]]
    (is (thrown? Exception (content/validate-member! {:github "valid" :joined "2026-10" :full-name name} "valid.edn"))))
  (doseq [member [{:github "-bad" :joined "2026-10"}
                  {:github "a--b" :joined "2026-10"}
                  {:github "valid" :joined "2026-13"}
                  {:github "valid" :joined "2026-10" :email "private"}
                  {:github "valid" :joined "2026-10" :interests ["elisp"]}]]
    (is (thrown? Exception (content/validate-member! member (str (:github member) ".edn")))))
  (is (thrown? Exception (content/unique! :test [{:id "same"} {:id "same"}] :id)))
  (is (thrown? Exception (content/validate-references! {:talks [{:id "x" :speaker "missing"}]})))
  (is (thrown? Exception (content/validate-references! {:snippets [{:id "x" :author "missing"}]})))
  (let [meeting example-meeting]
    (doseq [[field value] [[:date "2026-02-30"] [:time "25:00"] [:timezone "Not/AZone"]
                           [:duration-minutes 0] [:url "javascript:alert(1)"] [:body "<script>"]
                           [:discussion-number -1]]]
      (is (thrown? Exception (content/validate-content! :meetings (assoc meeting field value) (str (:id meeting) ".edn"))))))
  (is (false? (boolean (content/https-url? "https://user:secret@example.com/path")))))

(deftest real-content-is-not-mistaken-for-examples
  (let [meeting (-> example-meeting
                    (assoc :status :scheduled :example? false :date "2099-10-21"
                           :join-url "https://example.org/public-meeting")
                    calendar/enrich)
        updated (assoc data :meetings [meeting])
        home (html/render (view/view (state/initial updated "/" "/")))
        listing (html/render (view/view (state/initial updated "/" "/meetings/")))]
    (is (str/includes? home (:start-utc meeting)))
    (is (not (str/includes? listing "No meetings are currently scheduled."))))
  (let [member {:github "new-member" :joined "2026-10"}
        updated (assoc data :members [member] :snippets []
                       :pages (mapv #(dissoc % :author) (:pages data)))
        home (html/render (view/view (state/initial updated "/" "/")))]
    (is (str/includes? home "1 member, and room for you"))
    (is (not (str/includes? home "No real member records")))))

(deftest meeting-timeline
  (let [meeting (calendar/enrich example-meeting)
        earlier (calendar/enrich (assoc meeting :id "earlier-meeting" :date "2026-10-14" :time "13:00"))
        output (html/render (view/meeting-timeline start [meeting earlier]))]
    (is (str/includes? output "<ol"))
    (is (str/includes? output "Meeting timeline"))
    (is (= 2 (count (re-seq #"class=\"meeting-node\"" output))))
    (is (< (str/index-of output "2026-10-14") (str/index-of output "2026-10-21")))
    (is (str/includes? output "datetime=\"2026-10-14T11:00:00Z\""))
    (is (str/includes? output "Europe/Oslo"))
    (is (str/includes? output "Example, not live community data"))))

(deftest member-experience
  (let [person (step fixture-start [:buffer/open "/people/example-member/"])
        people (step fixture-start [:buffer/open "/people/"])
        minimal (assoc-in person [:content :members] [{:github "example-member" :joined "2026-10"}])]
    (is (str/includes? (content/visible-text (view/buffer-view person)) "Emacs since"))
    (is (str/includes? (content/visible-text (view/buffer-view people)) "Emacs since 2018"))
    (is (not (str/includes? (content/visible-text (view/buffer-view minimal)) "Emacs since")))))

(deftest member-avatars
  (let [member {:github "valid" :joined "2026-10"}
        output (html/render (view/member-avatar start member))]
    (is (= "/assets/avatars/valid.png" (github/avatar-path member)))
    (is (str/includes? output "src=\"/scratch-buffer/assets/avatars/valid.png\""))
    (is (str/includes? output "width=\"48\""))
    (is (str/includes? output "height=\"48\""))
    (is (str/includes? output "loading=\"lazy\""))
    (is (str/includes? output "decoding=\"async\""))
    (is (str/includes? output "alt=\"\""))
    (is (str/includes? output "aria-hidden=\"true\""))
    (is (str/includes? output ">V</span>"))
    (is (= "" (content/visible-text (view/member-avatar start member))))
    (is (not (str/includes? output "https://"))))
  (let [output (html/render (view/member-avatar start (first (:members fixtures/community))))]
    (is (not (str/includes? output "<img")))
    (is (str/includes? output ">E</span>"))))

(deftest names-and-authorship
  (let [member {:github "valid" :full-name "A Public Name" :joined "2026-10"}
        updated (-> fixture-data
                    (update :members conj member)
                    (update-in [:talks 0] assoc :author "valid"))
        s (state/initial updated "/scratch-buffer/" "/")
        route (router/resolve-route updated "/people/valid/")
        bylines (html/render (view/bylines s {:author "valid" :speaker "example-member"}))]
    (is (= "A Public Name" (router/member-name member)))
    (is (= "valid" (router/member-name (dissoc member :full-name))))
    (is (= "A Public Name" (:title route)))
    (is (= "*person/valid*" (:buffer route)))
    (is (str/includes? (html/render (view/people s)) "A Public Name"))
    (is (str/includes? (html/render (view/people s)) "@valid"))
    (is (str/includes? bylines "/scratch-buffer/assets/avatars/valid.png"))
    (is (str/includes? bylines "/scratch-buffer/people/valid/"))
    (is (str/includes? (content/visible-text (view/bylines s {:author "valid" :speaker "example-member"})) "Speaker"))
    (is (= 1 (count (re-seq #"class=\"member-byline\"" (html/render (view/bylines s {:author "valid" :speaker "valid"}))))))
    (is (nil? (view/bylines s {})))
    (is (thrown? Exception (view/bylines s {:author "missing"})))
    (let [upcoming (-> s
                       (assoc-in [:content :today] "2026-01-01")
                       (update-in [:content :meetings 0] assoc :author "valid" :status :scheduled :example? false))]
      (is (str/includes? (html/render (view/buffer-view upcoming)) "A Public Name")))
    (doseq [handle ["valid" "example-member"]]
      (let [person (state/initial updated "/" (str "/people/" handle "/"))]
        (is (str/includes? (html/render (view/buffer-view person)) "/meetings/talks/a-smaller-init/"))))
    (is (some #(= "/people/valid/" (:path %)) (commands/search (content/search-index updated) "A Public Name")))
    (is (some #(= "/meetings/talks/a-smaller-init/" (:path %)) (commands/search (content/search-index updated) "valid")))
    (let [unsafe (assoc member :full-name "<script>alert(1)</script>")
          s (assoc-in s [:content :members] [unsafe])]
      (is (str/includes? (html/render (view/member-byline s "valid" "By")) "&lt;script&gt;"))
      (is (not (str/includes? (html/render (view/member-byline s "valid" "By")) "<script>")))))
  (doseq [record (concat (:pages data) (:snippets data))
          :when (#{"scratch" "about" "eglot" "eldoc" "flymake" "icomplete-vertical"
                   "keep-your-place" "magit" "repeat-mode" "savehist" "which-key"} (:id record))]
    (is (= "hkjels" (:author record)))))

(deftest code-copy
  (let [text "  (message \"<script>&\")\n\n"
        result (state/transition start [:code/copy text])
        output (html/render (view/code-block text "Code example"))]
    (is (= start (:state result)))
    (is (= [[:clipboard/write text :code]] (:effects result)))
    (is (str/includes? output "class=\"code-copy dynamic-only\""))
    (is (str/includes? output "aria-label=\"Copy code to clipboard\""))
    (is (str/includes? output "type=\"button\""))
    (is (str/includes? output "data-search-exclude=\"true\""))
    (is (str/includes? output "&lt;script&gt;&amp;"))
    (is (not (str/includes? output "onclick")))
    (is (not (str/includes? (content/visible-text (view/code-block text "Code example")) "Copy code"))))
  (doseq [invalid [nil "" "  " 12 []]]
    (is (thrown? Exception (state/transition start [:code/copy invalid]))))
  (let [output (html/render (view/join-section start))]
    (is (not (str/includes? output "Copy member template")))
    (is (str/includes? output "Copy code to clipboard"))))

(deftest completion-polish
  (let [marked (fn [query text] (apply str (map :text (filter :match? (commands/highlight-parts query text)))))]
    (is (= "mee" (marked "MEE" "meetings")))
    (is (= "mtgs" (marked "mtgs" "meetings")))
    (is (= "dark" (marked "theme dark" "currently dark")))
    (is (= "" (marked "" "meetings")))
    (is (= "" (marked "unmatched" "meetings")))
    (is (= "theme" (marked "theme dark" "set-theme")))
    (doseq [query ["" "mee" "mtgs" "MEET" "<script>" "dark theme"]]
      (is (= "meetings" (apply str (map :text (commands/highlight-parts query "meetings")))))))
  (let [on-join (-> start (step [:buffer/open "/join/"]) (step [:minibuffer/open :commands]))
        on-meetings (-> start (step [:buffer/open "/meetings/"]) (step [:minibuffer/open :commands]))]
    (is (= :copy-member-template (:id (first (commands/candidates on-join)))))
    (is (= #{:submit-talk :suggest-topic :propose-meeting} (set (map :id (take 3 (commands/candidates on-meetings))))))
    (is (= (set (map :id commands/registry)) (set (map :id (commands/candidates on-join)))))
    (is (= :meetings (:id (first (commands/candidates (step on-meetings [:minibuffer/input "mee"])))))))
  (let [annotation (fn [s id] (:annotation (some #(when (= id (:id %)) %) (commands/candidates s))))]
    (is (= "currently dark" (annotation (step start [:theme/system true]) :toggle-theme)))
    (is (= "light mode" (annotation (step start [:theme/set :light]) :set-theme)))
    (is (= "enabled" (annotation (step start [:constellation/available true]) :toggle-animation)))
    (is (= "paused" (annotation (-> start (step [:constellation/available true]) (step [:constellation/toggle])) :toggle-animation)))
    (is (= "reduced motion" (annotation (step start [:constellation/reduced true]) :toggle-animation))))
  (is (= [[:clipboard/write (state/member-template start) :member-template]] (:effects (state/transition start [:member/copy-template]))))
  (is (re-matches #"\{:github \"your-github-handle\"\n :joined \"\d{4}-\d{2}\"\}" (state/member-template start)))
  (let [output (html/render (view/minibuffer (-> start (step [:minibuffer/open :commands]) (step [:minibuffer/input "mee"]))))]
    (is (str/includes? output "class=\"completion-match\">mee</strong>"))
    (is (str/includes? (content/visible-text (view/minibuffer (step start [:minibuffer/open :commands]))) "currently light"))))

(deftest tip-presentation
  (is (= [[:tip/choose]] (:effects (state/transition start [:tip/random]))))
  (let [other (step start [:buffer/open "/meetings/"])
        result (state/transition other [:tip/set 1])
        shown (:state result)
        updated (step shown [:tip/set 0])]
    (is (= 1 (get-in shown [:ui :tip])))
    (is (= (:route other) (:route shown)))
    (is (= (:buffer other) (:buffer shown)))
    (is (= [[:tip/expire-later 1]] (:effects result)))
    (is (= (second (filter :tip? (:snippets data))) (get-in shown [:ui :tip-notice])))
    (is (= updated (step updated [:tip/expire 1])))
    (is (nil? (get-in (step updated [:tip/expire 2]) [:ui :tip-notice])))
    (is (= [[:tip/cancel-expiry]] (:effects (state/transition shown [:tip/pause]))))
    (is (= [[:tip/expire-later 1]] (:effects (state/transition shown [:tip/resume]))))
    (is (nil? (get-in (step shown [:tip/dismiss]) [:ui :tip-notice])))
    (is (empty? (:effects (state/transition start [:tip/resume]))))
    (is (str/includes? (html/render (view/tip-notification shown)) "Read this tip"))
    (is (not (str/includes? (html/render (view/buffer-view start)) "tip-section"))))
  (is (thrown? Exception (state/transition start [:tip/set 999])))
  (let [empty-state (assoc-in start [:content :snippets] [])
        result (state/transition empty-state [:tip/random])]
    (is (empty? (:effects result)))
    (is (= "No tips have been published yet." (get-in (:state result) [:ui :notice])))))

(deftest calendar-and-time-zones
  (let [scheduled (assoc example-meeting :status :scheduled :example? false :join-url "https://example.org/meeting")
        summer (calendar/enrich scheduled)
        winter (calendar/enrich (assoc scheduled :date "2026-12-21"))]
    (is (= "2026-10-21T12:00:00Z" (:start-utc summer)))
    (is (= "2026-10-21T12:30:00Z" (:end-utc summer)))
    (is (= "UTC+02:00" (:utc-offset summer)))
    (is (= "2026-12-21T13:00:00Z" (:start-utc winter)))
    (is (= "UTC+01:00" (:utc-offset winter)))
    (is (= "UTC" (:utc-offset (calendar/enrich (assoc scheduled :timezone "UTC")))))
    (doseq [date ["2026-03-29" "2026-10-25"]]
      (is (thrown? Exception (content/validate-content! :meetings (assoc scheduled :date date :time "02:30") (str (:id scheduled) ".edn")))))
    (doseq [key [:notes-url :recording-url]]
      (let [with-link (assoc scheduled key "https://example.org/material")]
        (is (= with-link (content/validate-content! :meetings with-link (str (:id scheduled) ".edn")))))
      (is (thrown? Exception (content/validate-content! :meetings (assoc scheduled key "javascript:alert(1)") (str (:id scheduled) ".edn")))))
    (is (thrown? Exception (calendar/document data example-meeting)))
    (let [document (calendar/document data (assoc scheduled :title "Café, 🧠; editing\nBEGIN:VALARM"
                                                   :description (apply str (repeat 60 "á🧠"))
                                                   :location "A room, a desk; a chair"))
          unfolded (str/replace document "\r\n " "")]
      (is (str/includes? document "DTSTART:20261021T120000Z\r\n"))
      (is (str/includes? document "DTEND:20261021T123000Z\r\n"))
      (is (str/includes? unfolded "SUMMARY:Café\\, 🧠\\; editing\\nBEGIN:VALARM"))
      (is (str/includes? unfolded "LOCATION:A room\\, a desk\\; a chair"))
      (is (str/ends-with? document "END:VCALENDAR\r\n"))
      (is (not (str/includes? document "�")))
      (doseq [line (str/split document #"\r\n")]
        (is (<= (alength (.getBytes line "UTF-8")) 75)))
      (is (not (re-find #"(?<!\r)\n|\r(?!\n)" document))))
    (fs/with-temp-dir [out {}]
      (calendar/publish! out (assoc data :meetings [summer example-meeting (assoc summer :id "past" :status :past)]))
      (let [path (str out (meetings/calendar-path summer))]
        (is (fs/exists? path))
        (is (= 1 (count (fs/glob out "**/*.ics"))))
        (is (= (calendar/document data summer) (slurp path)))))
    (let [s (state/initial (assoc data :meetings [summer]) "/scratch-buffer/" "/meetings/example-first-gathering/")]
      (is (= [[:download/file "/scratch-buffer/meetings/example-first-gathering/event.ics" "example-first-gathering.ics"]]
             (:effects (state/transition s [:meeting/download]))))
      (is (str/includes? (html/render (view/buffer-view s)) "download=\"example-first-gathering.ics\""))))
  (let [future (calendar/enrich (assoc example-meeting :status :scheduled :example? false
                                     :date "2026-10-04" :time "00:30" :timezone "Pacific/Auckland"))
        content (assoc data :now "2026-10-03T11:45:00.000Z" :meetings [future])]
    (is (= future (meetings/next-meeting content)))
    (is (nil? (meetings/next-meeting (assoc content :now (:end-utc future)))))
    (is (nil? (meetings/next-meeting (assoc content :meetings [example-meeting])))))
  (is (str/includes? (get-in (step start [:meeting/download]) [:ui :notice]) "Example meetings are not invitations.")))

(deftest related-reading
  (let [origin {:id "same" :tags #{:editing :clojure}}
        content {:snippets [origin {:id "one" :tags #{:editing}} {:id "unrelated" :tags #{:mail}}]
                 :talks [{:id "sample" :tags #{:editing :clojure} :example? true}
                         {:id "real" :tags #{:editing}} {:id "same" :tags #{:editing :clojure}}]}
        result (reading/related content {:kind :snippet} origin)]
    (is (= ["/meetings/talks/same/" "/meetings/talks/real/" "/snippets/one/"] (mapv :path result)))
    (is (empty? (reading/related content {:kind :snippet} (dissoc origin :tags))))
    (is (some #(= "/meetings/talks/sample/" (:path %)) (reading/related content {:kind :snippet} (assoc origin :example? true)))))
  (let [s (step start [:buffer/open "/snippets/eglot/"])
        record (router/record data (:route s))
        output (html/render (view/related-reading s record))]
    (is (str/includes? output "Related reading"))
    (is (str/includes? output "/scratch-buffer/snippets/flymake/"))
    (is (not (str/includes? output "/scratch-buffer/snippets/eglot/")))
    (is (= "" (content/visible-text (view/related-reading s record)))))
  (let [updated (update data :snippets #(mapv (fn [record] (if (= "flymake" (:id record)) (assoc record :title "Distinct-neighbour-title") record)) %))
        index (content/search-index updated)
        snippet (some #(when (= "/snippets/eglot/" (:path %)) %) index)]
    (is (not (str/includes? (:text snippet) "distinct-neighbour-title")))))
(deftest meeting-reading
  (is (= ["/meetings/talks/other/"]
         (mapv :path (reading/related {:talks [{:id "attached" :meeting-id "event" :tags #{:editing}}
                                               {:id "other" :tags #{:editing}}]}
                                     {:kind :meeting} {:id "event" :tags #{:editing}})))))

(deftest consolidated-content
  (is (= #{:scratch :about :meetings :people :snippets :help :search :messages} (set (map :id router/pages))))
  (is (<= 9 (count (:snippets data))))
  (is (not (contains? data :packages)))
  (doseq [snippet (:snippets data)
          :when (and (:code snippet) (= "elisp" (:language snippet)))]
    (is (str/includes? (:code snippet) "(use-package")
        (:id snippet)))
  (let [people (view/buffer-view (step start [:buffer/open "/people/"]))
        output (html/render people)
        meetings (html/render (view/buffer-view (step start [:buffer/open "/meetings/"])))]
    (is (= 1 (count (re-seq #"Join the group" output))))
    (is (str/includes? output "Member entry template"))
    (is (str/includes? meetings "Talks &amp; material")))
  (let [meetings (html/render (view/buffer-view (step fixture-start [:buffer/open "/meetings/"])))]
    (is (str/includes? meetings "/meetings/talks/a-smaller-init/"))))

(deftest published-content
  (is (not-any? :example? (mapcat #(get data %) [:members :pages :meetings :talks :snippets])))
  (doseq [path ["/people/example-member/" "/meetings/example-first-gathering/"
                "/meetings/talks/a-smaller-init/" "/talks/a-smaller-init/"]]
    (is (= :not-found (:id (router/resolve-route data path))))
    (is (not-any? #(= path (:path %)) (:search data))))
  (let [empty-data (assoc data :members [] :meetings [] :talks [])
        meetings (html/render (view/buffer-view (state/initial empty-data "/" "/meetings/")))
        people (html/render (view/buffer-view (state/initial empty-data "/" "/people/")))]
    (is (str/includes? meetings "No meetings are currently scheduled."))
    (is (str/includes? meetings "No talks have been published yet."))
    (is (str/includes? people "0 members"))
    (is (str/includes? people "Join the group"))
    (is (not (str/includes? meetings "Example records")))))

(deftest constellation-geometry-and-cycling
  (let [names (get-in data [:constellation :names])
        model (constellation/initial names)
        advance-and-commit (fn [m]
                             (let [index (:cursor m)
                                   advanced (constellation/advance m)]
                               (constellation/commit advanced index (get-in advanced [:slots index :revision]))))
        models (take (inc (count names)) (iterate advance-and-commit model))
        inventory (fn [m] (concat (map :label (:slots m))
                                  (keep :incoming (:slots m)) (:queue m)))]
    (is (>= (count names) 100))
    (is (not-any? #{"python" "rst" "simple"} names))
    (is (= 18 constellation/slot-count))
    (is (= (zipmap (range 6) (repeat 3)) (frequencies (map :arm constellation/anchors))))
    (is (= (zipmap (range 3) (repeat 6)) (frequencies (map :ring constellation/anchors))))
    (doseq [{:keys [arm x y]} constellation/anchors]
      (is (<= 0 x 640))
      (is (<= 0 y 560))
      (if (#{0 3} arm)
        (is (= 320 x))
        (is (< (abs (- (abs (- x 320)) (* 1.7320508076 (abs (- y 280))))) 0.0001))))
    (doseq [label names]
      (is (<= (* 0.62 (count label) (constellation/label-size label)) 142.00001)))
    (is (= (set names) (set (mapcat #(map :label (:slots %)) models))))
    (doseq [m models]
      (is (= (count names) (count (inventory m))))
      (is (= (set names) (set (inventory m)))))
    (let [one (constellation/advance model)
          two (constellation/advance one)]
      (is (= (set names) (set (inventory two))))
      (is (= (count names) (count (inventory two))))
      (is (= (map :label (:slots model)) (map :label (:slots two))))
      (is (= "tramp" (get-in two [:slots 0 :label])))
      (is (= (constellation/commit two 0 1)
             (constellation/commit (constellation/commit two 0 1) 0 1)))
      (is (= two (constellation/commit two 0 99))))
    (let [revisited (constellation/advance (nth models constellation/slot-count))]
      (is (= 2 (get-in revisited [:slots 0 :revision])))
      (is (= revisited (constellation/commit revisited 0 1))))
    (is (thrown? Exception (constellation/advance (assoc model :queue []))))
    (is (thrown? Exception (nth (iterate constellation/advance model) (inc constellation/slot-count))))
    (doseq [[index revision] [[-1 1] [18 1] [0 0] [nil 1]]]
      (is (thrown? Exception (constellation/commit model index revision))))))

(deftest constellation-state-and-validation
  (let [active (reduce step start [[:constellation/available true]
                                   [:constellation/visible true]])
        advanced (state/transition active [:constellation/advance])]
    (is (false? (constellation/running? start)))
    (is (true? (constellation/running? active)))
    (is (= [[:constellation/fade 0 1]] (:effects advanced)))
    (is (= (first (get-in active [:ui :constellation :queue]))
           (get-in (:state advanced) [:ui :constellation :slots 0 :incoming])))
    (doseq [action [[:constellation/toggle] [:constellation/reduced true]
                    [:constellation/available false] [:constellation/visible false]
                    [:constellation/foreground false] [:minibuffer/open :commands]
                    [:buffer/open "/about/"]]]
      (is (false? (constellation/running? (step active action)))))
    (is (= [[:constellation/sync]] (:effects (state/transition active [:constellation/toggle]))))
    (is (= "Home illustration paused." (get-in (step active [:constellation/toggle]) [:ui :notice])))
    (is (= "Home illustration resumed."
           (get-in (-> active (step [:constellation/toggle]) (step [:constellation/toggle])) [:ui :notice])))
    (is (str/includes? (get-in (-> active (step [:constellation/reduced true]) (step [:constellation/toggle])) [:ui :notice])
                       "Reduced motion"))
    (is (true? (constellation/running? (-> active (step [:constellation/toggle]) (step [:constellation/toggle]))))))
  (let [record (:constellation data)]
    (is (= record (content/validate-constellation! record)))
    (doseq [invalid [{:names []}
                     {:names (take 36 (:names record))}
                     (update record :names conj "tramp")
                     (update record :names conj "<script>")
                     (update record :names conj "name-with-more-than-24-characters")
                     (assoc record :unknown true)]]
      (is (thrown? Exception (content/validate-constellation! invalid)))))
  (let [output (html/render (view/package-constellation start))
        home-text (content/visible-text (view/buffer-view start))
        home-search (some #(when (= "/" (:path %)) %) (:search data))]
    (is (str/includes? output "viewBox=\"0 0 640 560\""))
    (is (str/includes? output "aria-hidden=\"true\""))
    (is (= 18 (count (re-seq #"class=\"constellation-current\"" output))))
    (is (not (str/includes? output "<button")))
    (is (not (str/includes? output "figcaption")))
    (is (not (str/includes? output "names. One shared shape.")))
    (is (not (str/includes? home-text "tramp")))
    (is (not (str/includes? (:text home-search) "tramp")))))

(deftest safe-static-output
  (is (= "&lt;script&gt;&quot;&amp;" (html/escape-text "<script>\"&")))
  (is (not (str/includes? (html/render (view/view start)) "replicant/key")))
  (is (not (re-find #"\s(?:on|on[a-z]+)=" (html/render (view/view start)))))
  (doseq [data [data fixture-data]
          route (router/routes data)]
    (let [output (html/render (view/view (state/initial data "/" (:path route))))]
      (is (str/includes? output "<h1"))
      (is (str/includes? output "<main"))
      (is (str/includes? output "mode-line")))))

(deftest compatible-source-maps
  (let [section {:version 3 :file "runtime.js" :sources ["core.cljs"]
                 :sourcesContent ["(ns core)"] :names ["hello"]
                 :mappings "AAAA;AACA" :lineCount 2 :x_google_ignoreList [0]}]
    (doseq [offset [0 1 3]]
      (let [indexed {:version 3 :file "runtime.js"
                     :sections [{:offset {:line offset :column 0} :map section}]}
            flat (source-maps/flatten-indexed indexed)]
        (is (= (str (apply str (repeat offset ";")) "AAAA;AACA") (:mappings flat)))
        (is (= (+ 2 offset) (:lineCount flat)))
        (is (= (dissoc section :lineCount :mappings) (dissoc flat :lineCount :mappings)))
        (is (not (contains? flat :sections)))))
    (doseq [indexed [{:version 3 :file "runtime.js" :sections []}
                     {:version 3 :file "runtime.js"
                      :sections [{:offset {:line 0 :column 1} :map section}]}
                     {:version 3 :file "runtime.js"
                      :sections [{:offset {:line -1 :column 0} :map section}]}
                     {:version 3 :file "runtime.js"
                      :sections [{:offset {:line 0 :column 0} :map section}
                                 {:offset {:line 2 :column 0} :map section}]}]]
      (is (thrown? Exception (source-maps/flatten-indexed indexed))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'scratch.test-runner)]
    (when (pos? (+ fail error)) (System/exit 1))))
