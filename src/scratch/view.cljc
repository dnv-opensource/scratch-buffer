(ns scratch.view
  (:require [clojure.string :as str]
            [scratch.commands :as commands]
            [scratch.constellation :as constellation]
            [scratch.github :as github]
            [scratch.meetings :as meetings]
            [scratch.parentheses :as parentheses]
            [scratch.reading :as reading]
            [scratch.state :as state]
            [scratch.router :as router]))

(defn link [state path label & [attrs]]
  [:a (merge {:href (router/url (:base state) path)
              :on {:click [[:link/follow path]]}} attrs) label])

(defn button [label action & [attrs]]
  [:button (merge {:type "button" :on {:click [action]}} attrs) label])

(defn code-block [text label]
  [:div {:class "code-block"}
   [:pre {:tabindex 0 :aria-label label} [:code text]]
   (button
    [:svg {:viewBox "0 0 24 24" :width 18 :height 18 :fill "none"
           :stroke "currentColor" :stroke-width 1.5 :stroke-linecap "round"
           :stroke-linejoin "round" :aria-hidden "true"}
     [:rect {:x 9 :y 9 :width 12 :height 12 :rx 2}]
     [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]]
    [:code/copy text]
    {:class "code-copy dynamic-only" :aria-label "Copy code to clipboard"
     :title "Copy code to clipboard" :data-search-exclude "true"})])

(defn external [url label]
  [:a {:href url} label])

(defn teams-link [state label]
  [:a {:href (get-in state [:content :site :teams-url])
       :target "_blank" :rel "noopener noreferrer"
       :aria-label (str label " (opens in a new tab)")} label])

(defn paragraphs [body]
  (map-indexed (fn [i p] [:p {:replicant/key i} p]) body))

(defn tags [record]
  (when (seq (:tags record))
    [:p {:class "metadata"} (str/join " / " (sort (map name (:tags record))))]))

(defn example-label [record]
  (when (:example? record) [:span {:class "example-label"} "Example, not live community data"]))

(defn member-avatar [state member]
  [:span {:class "member-avatar" :aria-hidden "true"}
   [:span {:class "avatar-initial"} (str/upper-case (subs (:github member) 0 1))]
   (when-not (:example? member)
     [:img {:src (router/url (:base state) (github/avatar-path member))
            :alt "" :width 48 :height 48 :loading "lazy" :decoding "async"}])])

(defn member-byline [state handle label]
  (let [member (or (some #(when (= handle (:github %)) %) (get-in state [:content :members]))
                   (throw (ex-info "Unknown byline member" {:github handle})))]
    [:p {:class "member-byline"}
     (member-avatar state member)
     [:span label " " (link state (str "/people/" handle "/") (router/member-name member))]]))

(defn bylines [state {:keys [author speaker]}]
  (when (or author speaker)
    [:div {:class "bylines"}
     (when author (member-byline state author "By"))
     (when (and speaker (not= author speaker)) (member-byline state speaker "Speaker"))]))

(defn meeting-times [state record]
  [:div {:class "meeting-times"}
   [:p {:class "metadata meeting-clock"}
    [:time {:datetime (:start-utc record)} (str (or (:date-label record) (:date record)) " / " (:time record))]
    (str " / " (:timezone record) (when (:utc-offset record) (str " / " (:utc-offset record)))
         " / " (:duration-minutes record) " min")]
   (when-let [local (get-in state [:ui :meeting-times (:id record)])]
     [:p {:class "metadata meeting-local"}
      (str "Your time: " (:date local) " / " (:start local) " to "
           (when (not= (:date local) (:end-date local)) (str (:end-date local) " / "))
           (:end local) " / " (:timezone local))])])

(defn calendar-link [state record]
  (when (meetings/calendar? record)
    [:a {:href (router/url (:base state) (meetings/calendar-path record))
         :download (str (:id record) ".ics")} "Add to calendar"]))

(defn related-reading [state record]
  (when-let [related (seq (reading/related (:content state) (:route state) record))]
    [:section {:class "related-reading" :data-search-exclude "true"}
     [:h2 "Related reading"]
     [:ul {:class "related-list"}
      (for [other related]
        [:li {:replicant/key (:path other)}
         (example-label other)
         (link state (:path other) (:title other))
         [:span {:class "metadata"} (str (str/capitalize (name (:kind other))) " / "
                                         (str/join " / " (sort (map name (:shared-tags other)))))]])]]))

(defn section-label [number label]
  [:h2 {:class "section-label"} [:span {:aria-hidden "true"} number] label])

(defn item-list [state records prefix]
  [:ul {:class "reading-list"}
   (for [record records]
     [:li {:replicant/key (:id record)}
      [:div (example-label record)
       [:h3 (link state (str prefix (:id record) "/") (:title record))]
       (when (:date record)
         [:p {:class "metadata"} (str (:date record) " / " (:time record) " / " (:timezone record))])
       [:p (:description record)]
       (bylines state record)
       (tags record)]])])

(defn meeting-timeline [state records]
  [:ol {:class "meeting-timeline" :aria-label "Meeting timeline"}
   (for [record (sort-by (juxt :date :time :id) records)]
     [:li {:replicant/key (:id record) :class "meeting-entry"}
      [:div {:class "meeting-stamp"}
       [:time {:class "meeting-date" :datetime (:date record)} (:date record)]
       [:time {:class "meeting-time" :datetime (:start-utc record)} (:time record)]
       [:span {:class "meeting-timezone"} (:timezone record)]
       (when (:utc-offset record) [:span {:class "meeting-offset"} (:utc-offset record)])]
      [:span {:class "meeting-node" :aria-hidden "true"}]
      [:div {:class "meeting-content"}
       (example-label record)
       [:h3 (link state (str "/meetings/" (:id record) "/") (:title record))]
       [:p (:description record)]
       (bylines state record)
       (tags record)]])])

(defn package-constellation [state]
  (let [{:keys [slots paused? reduced?]} (get-in state [:ui :constellation])
        names (get-in state [:content :constellation :names])]
    [:div {:id "package-constellation" :replicant/key :constellation :class "hero-illustration package-constellation" :aria-hidden "true"
           :data-name-count (count names) :data-paused paused? :data-reduced reduced?}
     [:div {:class "hero-stage constellation-stage"}
      [:svg {:class "hero-svg constellation-svg" :viewBox "0 0 640 560"
             :aria-hidden "true" :focusable "false"}
       [:g {:class "constellation-field"}
        [:path {:class "constellation-rays"
                :d "M320 40V520 M77.5 140L562.5 420 M77.5 420L562.5 140"}]
        (map-indexed
         (fn [index {:keys [label incoming revision]}]
           (let [{:keys [arm ring x y]} (get constellation/anchors index)]
             [:g {:class "constellation-slot" :replicant/key index
                  :data-slot index :data-arm arm :data-ring ring :data-revision revision
                  :transform (str "translate(" x " " y ")")}
              [:rect {:class "constellation-knockout" :x -76 :y -18 :width 152 :height 32}]
              [:text {:class "constellation-current" :text-anchor "middle" :dominant-baseline "middle"
                      :font-size (constellation/label-size label) :opacity 1}
               label]
              (when incoming
                [:text {:class "constellation-incoming" :text-anchor "middle" :dominant-baseline "middle"
                        :font-size (constellation/label-size incoming) :opacity 0}
                 incoming])]))
         slots)
        [:path {:class "constellation-center" :d "M320 264V296 M306 272L334 288 M306 288L334 272"}]]]]]))

(defn parenthesis-mobile [state]
  (let [{:keys [paused? reduced?]} (get-in state [:ui :constellation])
        {:keys [index incoming revision]} (get-in state [:ui :orbit-word])
        names (get-in state [:content :constellation :names])]
    [:div {:id "parenthesis-mobile" :replicant/key :parentheses :class "hero-illustration parenthesis-mobile" :aria-hidden "true"
           :data-paused paused? :data-reduced reduced?}
     [:div {:class "hero-stage"}
      [:svg {:class "hero-svg" :viewBox "0 0 640 560" :aria-hidden "true" :focusable "false"}
       [:defs
        [:filter {:id "orbital-glow" :x "-30%" :y "-30%" :width "160%" :height "160%"}
         [:feGaussianBlur {:stdDeviation 2}]]
        (for [beam parentheses/beams
              :let [[x y] (parentheses/tail-start beam)]]
          [:linearGradient {:replicant/key (:index beam) :id (str "orbital-tail-" (:index beam))
                            :gradientUnits "userSpaceOnUse" :x1 x :y1 y :x2 (:radius beam) :y2 0}
           (for [[offset opacity] parentheses/tail-stops]
             [:stop {:replicant/key offset :offset offset :stop-color "currentColor" :stop-opacity opacity}])])]
       [:g {:transform "translate(320 280)"}
        (for [{:keys [index radius phase] :as beam} parentheses/beams
              :let [path (parentheses/tail-path beam)
                    stroke (str "url(#orbital-tail-" index ")")]]
          [:g {:replicant/key index :class "beam-plane" :transform (parentheses/plane beam)}
           [:g {:class "orbital-beam" :data-beam index :transform (str "rotate(" phase ")")}
            [:path {:class "beam-halo" :d path :stroke stroke :filter "url(#orbital-glow)"}]
            [:path {:class "beam-tail" :d path :stroke stroke}]
            [:circle {:class "beam-head" :cx radius :cy 0 :r 2.5}]]])]
       [:g {:class "orbit-word" :data-slot "word" :data-revision revision}
        [:text {:class "orbit-word-current" :x 320 :y 280 :text-anchor "middle" :dominant-baseline "middle"
                :font-size (constellation/label-size (get names index)) :opacity 1}
         (get names index)]
        (when incoming
          [:text {:class "orbit-word-incoming" :x 320 :y 280 :text-anchor "middle" :dominant-baseline "middle"
                  :font-size (constellation/label-size (get names incoming)) :opacity 0}
           (get names incoming)])]]]]))

(defn hero-illustration [state]
  (case (get-in state [:ui :hero])
    :constellation (package-constellation state)
    :parentheses (parenthesis-mobile state)))

(defn scratch [state]
  (let [data (:content state)
        page (some #(when (= "scratch" (:id %)) %) (:pages data))
        upcoming (meetings/next-meeting data)
        members (remove :example? (:members data))]
    [:div
     [:div {:class "home-hero"}
      [:div {:class "home-intro"}
       [:p {:class "eyebrow"} "A shared space for a very personal editor"]
       [:h1 {:class "home-title"} "DNV Emacs" [:br] "User Group" [:span {:class "title-stop" :aria-hidden "true"} "."]]
       [:p {:class "lede"} (:description page)]
       [:p {:class "intro-note"} (first (:body page))]
       (bylines state page)
       [:div {:class "intro-links"}
        (link state "/about/" "Meet the group")
        (link state "/people/#join" "How to join")
        (button "Find a command" [:minibuffer/open :commands] {:class "dynamic-only"})]]
      (hero-illustration state)]
     [:section
      (section-label "01" "Next up")
      (if upcoming
        [:div {:class "next-meeting"}
         (meeting-times state upcoming)
         [:h3 (link state (str "/meetings/" (:id upcoming) "/") (:title upcoming))]
         [:p (:description upcoming)]
         (bylines state upcoming)
         (calendar-link state upcoming)]
        [:div {:class "next-meeting"}
         [:h3 "The next gathering starts with an idea."]
         [:p "No meetings are scheduled yet. A short demo, a problem to solve, or a question is enough."]
         (external (github/discussion-url state :topics) "Suggest a meeting topic on GitHub")])]
     [:section
      (section-label "02" "Worth sharing")
      (item-list state (take 2 (:snippets data)) "/snippets/")
      (link state "/meetings/#talks" "Talks & material")]
     [:section
      (section-label "03" "From the group")
      [:p "Small contributions make this place useful. Published notes live in the repository. Chat in Teams, or continue a longer conversation in GitHub Discussions."]
      [:p {:class "metadata"} "No live activity feed is loaded. Published content is included in this site build."]
      [:div {:class "intro-links"}
       (teams-link state "Open Teams chat")
       (external (github/discussion-url state :page) "Open Discussions")
       (external (github/discussion-url state :source) "Browse the repository")]]
     [:section {:class "join-note"}
      [:p {:class "eyebrow"} (if (seq members)
                               (str (count members) (if (= 1 (count members)) " member" " members") ", and room for you")
                               "A group is made by its people")]
      [:h2 "One small contribution." [:br] "You’re in."]
      [:p "An Emacs tip. A typo fix. A useful question turned into a note. Participation matters more than polish."]
      (link state "/people/#join" "Join the user group")
      (when (empty? members)
        [:p {:class "metadata"} (str "No real member records have been added yet."
                                     (when (some :example? (:members data))
                                       " The directory includes a clearly marked example."))])]]))

(defn join-section [state]
  (let [repo (get-in state [:content :site :repository])]
    [:section {:id "join" :class "join-section" :tabindex -1 :aria-labelledby "join-heading"}
     [:h2 {:id "join-heading"} "Join the group"]
     [:p "Add a member entry and one small useful contribution: a tip, a package configuration, or a typo fix. Open a pull request; once merged, you are a member."]
     [:p "Save this as members/your-github-handle.edn, replacing the placeholder with your own handle."]
     (code-block (state/member-template state) "Member entry template")
     [:p {:class "metadata"} "Only your GitHub handle and joining month are required. Other profile fields are optional. Everything published here is public."]
     [:div {:class "intro-links"}
      (external (str repo "/blob/main/members/README.md") "Member record guide")
      (external (str repo "/blob/main/CONTRIBUTING.md") "Contribution guide")]]))

(defn about [state]
  (let [page (some #(when (= "about" (:id %)) %) (get-in state [:content :pages]))]
    [:div
     [:p {:class "eyebrow"} "People before configuration"]
     [:h1 (:title page)]
     [:p {:class "lede"} (:description page)]
     (bylines state page)
     (paragraphs (:body page))
     [:h2 "A website, not an editor"]
     [:p "The fixed bottom bar tells you which page you are reading. Select the buffer name to switch pages, or open M-x to find any action. Nothing here requires Emacs knowledge."]
     [:p (link state "/help/" "See the keyboard and pointer guide") ". " (link state "/people/#join" "Join with a small contribution") "."]
     [:h2 "Between gatherings"]
     [:p "The scratch-buffer Teams chat is a place for quick questions and conversation."]
     (teams-link state "Open Teams chat")
     [:h2 "Public by design"]
     [:p "This site has no analytics, corporate directory integration, or custom login. Member records are explicit and minimal. Never publish confidential notes or credentials."]
     [:p {:class "community-note"} (get-in state [:content :site :status])]]))

(defn people [state]
  (let [members (get-in state [:content :members]) real (remove :example? members)]
    [:div
     [:p {:class "eyebrow"} "A directory, not a contributor graph"]
     [:h1 "People"]
     [:p {:class "lede"} (str (count real) (if (= 1 (count real)) " member" " members")
                              ". Every record is a small act of participation.")]
     [:ul {:class "people-list"}
      (for [member members]
        [:li {:replicant/key (:github member)}
         (member-avatar state member)
         [:div {:class "member-details"}
          (example-label member)
          [:h2 (link state (str "/people/" (:github member) "/") (router/member-name member))]
          (when (:full-name member) [:p {:class "metadata member-handle"} (str "@" (:github member))])
          (when (:emacs-since member)
            [:p {:class "metadata"} (str "Emacs since " (:emacs-since member))])
          [:p {:class "metadata"} (str/join " / " (sort (map name (:interests member))))]
          (when (:favorite-command member) [:p {:class "mono"} (str "M-x " (:favorite-command member))])]])]
     (join-section state)]))

(defn person [state record]
  [:div
   [:p {:class "eyebrow"} "Member record"]
   [:h1 (router/member-name record)]
   (when (:full-name record) [:p {:class "metadata member-handle"} (str "@" (:github record))])
   (example-label record)
   [:p {:class "metadata"} (str "Joined " (:joined record))]
   (when (:emacs-since record)
     [:p {:class "metadata"} "Emacs since " [:time {:datetime (:emacs-since record)} (:emacs-since record)]])
   (when (:bio record) [:p {:class "lede"} (:bio record)])
   (when (seq (:interests record))
     [:section [:h2 "Interests"] [:p (str/join " / " (sort (map name (:interests record))))]])
   (when (:favorite-command record)
     [:section [:h2 "A favorite command"] [:p {:class "mono"} (str "M-x " (:favorite-command record))]])
   (if (:example? record)
     [:p "This is a fictional example record for contributors. It is not a person or a membership claim."]
     (external (str "https://github.com/" (:github record)) "Public GitHub profile"))
   (for [[collection _ prefix] router/collections
         :let [records (filter #(or (= (:github record) (:author %))
                                   (= (:github record) (:speaker %)))
                              (get-in state [:content collection]))]
         :when (seq records)]
     [:section {:replicant/key collection}
      [:h2 (str/capitalize (name collection))]
      (if (= collection :meetings)
        (meeting-timeline state records)
        (item-list state records prefix))])
   [:p (link state "/people/" "Back to people")]])

(defn collection-view [state kind title description]
  (let [records (get-in state [:content kind])
        prefix (str "/" (name kind) "/")
        scheduled? (when (= kind :meetings) (meetings/next-meeting (:content state)))]
    [:div
     [:p {:class "eyebrow"} "Published knowledge"]
     [:h1 title]
     [:p {:class "lede"} description]
     (when (= kind :snippets)
       [:p "These configurations use use-package, built into Emacs 29 and later; install it separately on older versions. "
        [:code ":ensure nil"] " uses a built-in package. "
        [:code ":ensure t"] " installs an external package from your configured archives, so choose archives you trust."])
     (when (and (= kind :meetings) (not scheduled?))
       [:div {:class "empty-note"}
        [:h2 "No meetings are currently scheduled."]
        [:p (if (some :example? records)
              "Example records are not invitations. Real meetings will include a date, time zone, and joining details."
              "Suggest a short demo, a question, or something you would like to learn together.")]
        (external (github/discussion-url state :topics) "Propose a meeting on GitHub")])
     (if (seq records)
       (if (= kind :meetings)
         (meeting-timeline state records)
         (item-list state records prefix))
       [:p "Nothing published here yet. Your first small contribution could change that."])
     [:p (link state "/people/#join" "Contribute something useful")]]))

(defn meetings-view [state]
  [:div
   (collection-view state :meetings "Meetings" "Gatherings, short demos, and material worth returning to.")
   [:section {:id "talks" :tabindex -1 :aria-labelledby "talks-heading"}
    [:h2 {:id "talks-heading"} "Talks & material"]
    (if (seq (get-in state [:content :talks]))
      (item-list state (get-in state [:content :talks]) "/meetings/talks/")
      [:p "No talks have been published yet. A five-minute demo is enough to start."])
    (external (github/discussion-url state :talks) "Propose a talk on GitHub")]])

(defn detail [state record]
  [:div
   [:p {:class "eyebrow"} (name (:kind (:route state)))]
   [:h1 (:title record)]
   (example-label record)
   [:p {:class "lede"} (:description record)]
   (when (= :meeting (get-in state [:route :kind]))
     (meeting-times state record))
   (when (:location record) [:p (:location record)])
   (when (meetings/calendar? record)
     [:div {:class "intro-links meeting-actions"}
      (when (:join-url record) (external (:join-url record) "Join this meeting"))
      (calendar-link state record)])
   (bylines state record)
   (tags record)
   (paragraphs (:body record))
   (when-let [meeting (some #(when (= (:meeting-id record) (:id %)) %) (get-in state [:content :meetings]))]
     [:p "From " (link state (str "/meetings/" (:id meeting) "/") (:title meeting)) "."])
   (when (:code record)
     (code-block (:code record) "Code example"))
   (when (:url record) [:p (external (:url record) "Read the documentation")])
   (when (or (:notes-url record) (:recording-url record))
     [:section {:class "meeting-material"}
      [:h2 "Meeting material"]
      [:div {:class "intro-links"}
       (when (:notes-url record) (external (:notes-url record) "Read meeting notes"))
       (when (:recording-url record) (external (:recording-url record) "Watch the recording"))]])
   (when (= :meeting (get-in state [:route :kind]))
     (when-let [talks (seq (filter #(= (:id record) (:meeting-id %)) (get-in state [:content :talks])))]
       [:section [:h2 "Talks from this meeting"] (item-list state talks "/meetings/talks/")]))
   (related-reading state record)])

(defn discussion-time [timestamp]
  [:time {:datetime timestamp} (str (str/replace (subs timestamp 0 19) "T" " ") " UTC")])

(defn discussion-author [author]
  (if author (external (:url author) (:login author)) "Deleted GitHub account"))

(defn discussion-comment [comment reply?]
  [:li {:replicant/key (:url comment)}
   [:article {:class (if reply? "discussion-reply" "discussion-comment")}
    [:p {:class "metadata"} (discussion-author (:author comment)) " / " (discussion-time (:created-at comment))]
    [:p {:class "discussion-body"} (:body comment)]
    (external (:url comment) "View comment on GitHub")]
   (when (seq (:replies comment))
     [:ol {:class "discussion-replies"}
      (for [reply (:replies comment)] (discussion-comment reply true))])])

(defn discussion-content [thread]
  [:div {:class "discussion-content"}
   [:p {:class "metadata"} (discussion-author (:author thread)) " / " (discussion-time (:created-at thread))]
   [:p {:class "discussion-body"} (:body thread)]
   (if (seq (:comments thread))
     [:div
      [:h4 "Comments"]
      [:ol {:class "discussion-comments"}
       (for [comment (:comments thread)] (discussion-comment comment false))]]
     [:p {:class "metadata"} "No comments in this snapshot."])
   (external (:url thread) "Reply on GitHub")])

(defn discussion-section [state]
  (when (github/readable? state)
    (let [{:keys [status snapshot error]} (:github state)
          number (github/discussion-number state)
          thread (some #(when (= number (:number %)) %) (:discussions snapshot))]
      [:section {:class "discussion-note" :aria-labelledby "discussion-heading"
                 :aria-busy (str (= :loading status)) :data-search-exclude "true"}
       [:h2 {:id "discussion-heading"} (if number "Discussion" "From GitHub Discussions")]
       [:p "Read the conversation here. Posting and replies happen on GitHub."]
       [:p {:class "static-only"} "Read-only snapshots load with JavaScript. You can also read directly on GitHub."]
       [:div {:class "dynamic-only"}
        [:p {:class "discussion-status metadata" :role "status" :aria-live "polite"}
         (case status
           :idle "Discussion snapshot will load when this buffer opens."
           :loading [:span [:span {:class "loading-indicator" :aria-hidden "true"}] "Loading discussion snapshot..."]
           :error error
           :ready (case (:status snapshot)
                    "unavailable" "No discussion snapshot was included in this build."
                    "disabled" "GitHub Discussions were not enabled when this snapshot was built."
                    "ready" [:span "Read-only snapshot / Updated " (discussion-time (:generated-at snapshot))]))]
        (when (= :error status)
          (button "Retry loading discussions" [:github/load-snapshot true]))
        (when (and (= :ready status) (= "ready" (:status snapshot)))
          (if number
            (if thread
              [:div [:h3 (:title thread)] (discussion-content thread)]
              [:p "This thread is not in the published snapshot. It may be newer than the snapshot, or it may have been removed."])
            (if (seq (:discussions snapshot))
              [:div
               [:p {:class "metadata"} "Recent threads / Open a title to read its snapshot."]
               (for [thread (take 6 (:discussions snapshot))]
                 [:details {:class "discussion-thread" :replicant/key (:number thread)}
                  [:summary [:h3 (:title thread)]]
                  (discussion-content thread)])
               (when (> (count (:discussions snapshot)) 6)
                 [:p {:class "metadata"} "Showing the six most recently updated threads. More discussions are available on GitHub."])]
              [:p "No discussions in this snapshot yet. Start a conversation on GitHub."])))]
       (external (github/discussion-url state :page) "Discuss this page on GitHub")])))

(defn help [state]
  [:div
   [:p {:class "eyebrow"} "No Emacs experience required"]
   [:h1 "A little help"]
   [:p {:class "lede"} "Use the links. Or take a shortcut."]
   [:p "M-x is the command menu above the bottom bar. Type a few letters, choose a result, and go. The page stays visible underneath."]
   [:p "Tap M-x again to close the menu without running a command. Escape or Ctrl + g does the same from the keyboard."]
   [:dl {:class "key-guide"}
    (for [[key description] [["Alt + x" "Open commands (M-x)"]
                             ["Ctrl + x, then b" "Switch buffers"]
                             ["Ctrl + s" "Search this site (outside editable fields)"]
                             ["↑ / ↓" "Select a completion"]
                             ["Enter" "Run the selected command"]
                             ["Escape / Ctrl + g" "Close commands and return focus"]
                             ["Alt + p / Alt + n" "Previous / next command history"]
                             ["Tab / Shift + Tab" "Move between controls"]]]
      [:div {:replicant/key key} [:dt [:kbd key]] [:dd description]])]
   [:p "On macOS, use Option for Alt if your keyboard makes the shortcut available. Browser and operating-system shortcuts can take priority; the M-x button always works."]
   [:div {:class "intro-links dynamic-only"}
    (button "Open commands" [:minibuffer/open :commands])
    (button "Search" [:minibuffer/open :search])
    (button "Choose a theme" [:minibuffer/open :themes])
    (button "Install this site" [:pwa/install])]
   [:h2 "All pages"]
   [:ul {:class "page-directory"}
    (for [page router/pages :when (not (#{:messages :help :search} (:id page)))]
      [:li {:replicant/key (:path page)} (link state (:path page) (:title page))])]
   [:h2 "Appearance"]
   [:p "Light, dark, or your device’s theme. Your explicit choice stays in this browser. System mode follows your device automatically."]
   [:p "To pause or resume the home illustration, open M-x and choose toggle-animation. Reduced-motion preferences always keep it still."]
   [:h2 "Offline & installation"]
   [:p "After the offline cache is ready, published pages and saved discussion snapshots can be read without a connection. Posting and contribution links still need GitHub."]
   [:p "Use Install in the command menu where supported. Otherwise, use your browser’s install menu, or Share → Add to Home Screen on iPhone and iPad."]])

(defn search-page [state]
  [:div
   [:p {:class "eyebrow"} "Across every published buffer"]
   [:h1 "Search the site"]
   [:p {:class "lede"} "A command, an idea, a person."]
   (button "Open site search" [:minibuffer/open :search] {:class "dynamic-only"})
   [:p {:class "static-only"} "Interactive search needs JavaScript. You can browse all indexed content below, or use your browser’s Find command."]
   [:ul {:class "reading-list"}
    (for [entry (get-in state [:content :search])]
      [:li {:replicant/key (:path entry)}
       [:h2 (link state (:path entry) (:title entry))]
       [:p {:class "metadata"} (:type entry)]
       [:p (:description entry)]])]])

(defn buffer-view [state]
  (let [route (:route state)]
    (case (:id route)
      :scratch (scratch state)
      :about (about state)
      :people (people state)
      :person (person state (router/record (:content state) route))
      :meetings (meetings-view state)
      :snippets (collection-view state :snippets "Snippets & tips" "Configurations, package recommendations, and small things worth trying.")
      :help (help state)
      :search (search-page state)
      :messages [:div [:p {:class "eyebrow mono"} "*Messages*"] [:h1 "Messages"]
                 [:p (or (get-in state [:ui :notice]) "No messages. Everything is quiet.")]
                 [:p (if (get-in state [:pwa :offline?])
                       "Offline. Showing cached published buffers and saved discussion snapshots. Posting on GitHub needs a connection."
                       "Published content and read-only discussion snapshots are local to this site. Posting happens on GitHub.")]
                 (link state "/" "Return to the beginning")]
      :not-found [:div [:p {:class "eyebrow mono"} "*Messages*"] [:h1 "That buffer isn’t here."]
                  [:p "The link may be out of date, or the page may not have been published."]
                  (link state "/" "Return to the beginning")]
      (detail state (router/record (:content state) route)))))

(defn mode-line [state]
  [:footer {:class "mode-line" :aria-label "Mode-line"}
   [:span {:class "mode-brand"} "DNV"]
   (button (get-in state [:buffer :current]) [:minibuffer/open :buffers]
           {:class "mode-buffer dynamic-only" :aria-label (str "Switch buffer: " (get-in state [:buffer :current]))})
   [:span {:class "mode-buffer static-only"} (get-in state [:buffer :current])]
   (link state "/about/" "Emacs User Group" {:class "mode-group"})
   [:span {:class "mode-spacer"}]
   [:span {:class "mode-scroll" :aria-label "Scroll position"} (get-in state [:ui :scroll])]
   (button (name (get-in state [:theme :mode])) [:minibuffer/open :themes]
           {:class "mode-theme dynamic-only" :aria-label "Choose color theme"})
   [:span {:class "mode-encoding"} "UTF-8"]
   (button [:span "M-x" [:span {:class "mode-command-label"} " Commands"]]
           (if (get-in state [:minibuffer :open?]) [:minibuffer/cancel] [:minibuffer/open :commands])
           {:id "mx-button" :class "mode-command dynamic-only"
            :aria-label (if (get-in state [:minibuffer :open?]) "M-x: close commands" "M-x: open commands")
            :aria-expanded (boolean (get-in state [:minibuffer :open?]))
            :aria-controls (when (get-in state [:minibuffer :open?]) "minibuffer")})
   (link state "/help/" "Help" {:class "static-only mode-command"})])

(defn highlighted [query text]
  ;; Replace query-dependent fragments without remounting their completion option.
  [:span {:replicant/key [query text]}
   (map (fn [part]
          (if (:match? part)
            [:strong {:class "completion-match"} (:text part)]
            (:text part)))
        (commands/highlight-parts query text))])

(defn minibuffer [state]
  (when (get-in state [:minibuffer :open?])
    (let [{:keys [kind query selection]} (:minibuffer state)
          candidates (commands/candidates state)
          label (case kind :search "Search" :buffers "Switch buffer" :themes "Set theme" "M-x")
          active (when (seq candidates) (str "candidate-" selection))]
      [:section {:id "minibuffer" :class "minibuffer" :role "dialog" :aria-modal "false" :aria-label "Command interface"}
       [:div {:class "minibuffer-inner"}
        [:div {:class "minibuffer-prompt"}
         [:label {:for "command-input" :class "mono"} label]
         [:input {:id "command-input" :type "text" :value query :placeholder (if (= kind :search) "Search titles and body text…" "Type a few letters…")
                  :autocomplete "off" :spellcheck false :role "combobox" :aria-expanded "true"
                  :aria-autocomplete "list" :aria-controls "completions" :aria-activedescendant active
                  :on {:input [[:minibuffer/input [:event/value]]]}}]]
        [:p {:class "completion-status" :role "status" :aria-live "polite"}
         (str (count candidates) (if (= 1 (count candidates)) " result" " results"))
         [:span {:class "completion-hint"} "↑ ↓ select · Enter run · Esc / M-x close"]]
        (if (seq candidates)
          [:ul {:id "completions" :class "completions" :role "listbox" :aria-label label}
           (map-indexed
            (fn [i candidate]
              [:li {:id (str "candidate-" i) :replicant/key (str (:name candidate) (:path candidate))
                    :role "option" :aria-selected (= i selection)
                    :class (when (= i selection) "selected")
                    :on {:pointermove [[:minibuffer/select i]]
                         :click [[:command/execute candidate]]}}
               [:span {:class "candidate-name"}
                (highlighted query (:name candidate))]
               [:span {:class "candidate-description"}
                [:span {:class "candidate-summary"} (highlighted query (:description candidate))]
                (when (:annotation candidate)
                  [:span {:class "candidate-annotation"}
                   (highlighted query (:annotation candidate))])]])
            candidates)]
          [:div {:id "completions" :role "listbox" :aria-label label :class "no-results"}
           [:p (if (str/blank? query) "Type to search all published content." (str "No result matching “" query "”."))]])]])))

(defn tip-notification [state]
  [:div {:role "status" :aria-live "polite" :aria-atomic "true"}
   (when-let [tip (get-in state [:ui :tip-notice])]
     [:aside {:class "tip-notification" :aria-label "Random Emacs tip"
              :on {:pointerenter [[:tip/pause]] :pointerleave [[:tip/resume]]
                   :focusin [[:tip/pause]] :focusout [[:tip/resume]]}}
      [:p {:class "mono tip-command"} (str "M-x " (:command tip))]
      [:p (:description tip)]
      [:div {:class "intro-links"}
       (link state (str "/snippets/" (:id tip) "/") "Read this tip")
       (button "Dismiss" [:tip/dismiss])]])])

(defn view [state]
  [:div {:class "site"}
   [:a {:href "#buffer" :class "skip-link"} "Skip to content"]
   [:header {:class "site-header"}
    (link state "/" [:span [:span {:class "brand-mark" :aria-hidden "true"} "*_"] [:span {:class "brand-name mono"} "scratch-buffer"]]
          {:class "brand" :aria-label "scratch-buffer home"})
    [:nav {:class "site-navigation" :aria-label "Main navigation"}
     (for [page (filter #(#{:about :meetings :people :snippets} (:id %)) router/pages)]
       (link state (:path page) (str/capitalize (name (:id page)))
             {:replicant/key (:path page)
              :aria-current (when (str/starts-with? (get-in state [:route :path]) (:path page)) "page")}))
     [:span {:class "nav-underline" :aria-hidden "true"}]]]
   [:main {:id "buffer" :tabindex -1}
    (when (get-in state [:pwa :offline?])
      [:aside {:class "network-note" :role "status"} "Offline. Showing cached published buffers and saved discussion snapshots. Posting on GitHub and Teams chat need a connection."])
    (when-let [notice (get-in state [:ui :notice])]
      [:aside {:class "notice" :role "status"}
       [:p notice] (button "Dismiss" [:ui/dismiss] {:class "dynamic-only"})])
    [:article {:replicant/key (get-in state [:route :path]) :class "buffer-content"}
     (buffer-view state)
     (discussion-section state)]
    [:div {:class "page-colophon"}
     [:p (get-in state [:content :site :status])]
     [:div (link state "/help/" "Help & shortcuts") " · "
      (link state "/search/" "Search") " · "
      (teams-link state "Teams chat") " · "
      (external (github/discussion-url state :source) "Source")]]]
   (tip-notification state)
   (minibuffer state)
   (mode-line state)])
