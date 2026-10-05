(ns scratch.commands
  (:require [clojure.string :as str]
            [scratch.router :as router]
            [scratch.meetings :as meetings]
            [scratch.theme :as theme]))

(def registry
  [{:id :home :name "scratch-buffer" :description "Return to the beginning" :category :navigation :action [:buffer/open "/"]}
   {:id :about :name "about" :description "What this group is about" :category :navigation :action [:buffer/open "/about/"]}
   {:id :meetings :name "meetings" :description "Gatherings, talks, and material" :category :navigation :action [:buffer/open "/meetings/"]}
   {:id :snippets :name "snippets" :description "Configurations, packages, and tips" :category :navigation :action [:buffer/open "/snippets/"]}
   {:id :people :name "people" :description "The member directory and how to join" :category :navigation :action [:buffer/open "/people/"]}
   {:id :search :name "search" :description "Find something across all buffers" :category :interaction :action [:minibuffer/open :search]}
   {:id :switch-buffer :name "switch-buffer" :description "Choose a page to read" :category :interaction :action [:minibuffer/open :buffers]}
   {:id :previous-buffer :name "previous-buffer" :description "Return to the last buffer" :category :navigation :action [:buffer/previous]}
   {:id :describe-mode :name "describe-mode" :description "How this website works" :category :help :action [:buffer/open "/about/"]}
   {:id :describe-key :name "describe-key" :description "Keyboard shortcuts, without the mystery" :category :help :action [:buffer/open "/help/"]}
   {:id :join-user-group :name "join-user-group" :description "One small contribution is enough" :category :navigation :action [:buffer/open "/people/#join"]}
   {:id :submit-talk :name "submit-talk" :description "Start a talk proposal on GitHub" :category :community :contexts #{:meetings :meeting :talk} :action [:github/open :talks]}
   {:id :suggest-topic :name "suggest-topic" :description "Suggest a meeting topic on GitHub" :category :community :contexts #{:meetings :meeting} :action [:github/open :topics]}
   {:id :propose-meeting :name "propose-meeting" :description "Help arrange the next gathering" :category :community :contexts #{:meetings :meeting} :action [:github/open :topics]}
   {:id :ask-question :name "ask-question" :description "Ask the group on GitHub Discussions" :category :community :action [:github/open :questions]}
   {:id :teams :name "teams" :description "Open the scratch-buffer chat in Microsoft Teams" :category :community :action [:teams/open]}
   {:id :discuss-page :name "discuss-page" :description "Continue the conversation on GitHub" :category :community :contexts #{:meeting :talk :snippet} :action [:github/open :page]}
   {:id :copy-member-template :name "copy-member-template" :description "Copy the minimal EDN member entry" :category :community :contexts #{:people :person} :action [:member/copy-template]}
   {:id :add-to-calendar :name "add-to-calendar" :description "Download this meeting as a calendar event" :category :content :action [:meeting/download]}
   {:id :random-tip :name "random-tip" :description "Show a brief notification with an Emacs tip" :category :content :action [:tip/random]}
   {:id :toggle-theme :name "toggle-theme" :description "Switch between light and dark" :category :appearance :action [:theme/toggle]}
   {:id :set-theme :name "set-theme" :description "Choose system, light, or dark" :category :appearance :action [:minibuffer/open :themes]}
   {:id :toggle-animation :name "toggle-animation" :description "Pause or resume the home illustration" :category :appearance :action [:constellation/toggle]}
   {:id :source :name "source" :description "Read or contribute to this repository" :category :community :action [:github/open :source]}
   {:id :install :name "install" :description "Keep this site close at hand" :category :application :action [:pwa/install]}])

(defn words [s]
  (remove str/blank? (str/split (str/lower-case (str/trim s)) #"\s+")))

(defn subsequence? [needle haystack]
  (loop [n (seq needle) h (seq haystack)]
    (cond
      (nil? n) true
      (nil? h) false
      (= (first n) (first h)) (recur (next n) (next h))
      :else (recur n (next h)))))

(defn highlight-parts [query text]
  (let [lower (str/lower-case text)
        query (str/lower-case (str/trim query))
        exact (reduce
               (fn [indices term]
                 (loop [from 0 indices indices]
                   (if-let [index (str/index-of lower term from)]
                     (recur (+ index (count term)) (into indices (range index (+ index (count term)))))
                     indices)))
               #{} (words query))
        indices (if (and (seq query) (empty? exact) (subsequence? query lower))
                  (loop [needle (seq query) index 0 indices #{}]
                    (if (empty? needle)
                      indices
                      (if (= (first needle) (nth lower index))
                        (recur (next needle) (inc index) (conj indices index))
                        (recur needle (inc index) indices))))
                  exact)]
    (mapv (fn [characters]
            {:text (apply str (map second characters))
             :match? (contains? indices (ffirst characters))})
          (partition-by #(contains? indices (first %)) (map-indexed vector text)))))

(defn match-score [query candidate]
  (let [query (str/lower-case (str/trim query))
        label (str/lower-case (:name candidate))
        text (str label " " (str/lower-case (:description candidate)) " " (str/lower-case (or (:annotation candidate) "")))]
    (cond
      (str/blank? query) 0
      (str/starts-with? label query) 0
      (str/includes? label query) 1
      (every? #(str/includes? text %) (words query)) 2
      (subsequence? query label) 3
      :else nil)))

(defn match-commands [query candidates]
  (->> candidates
       (keep #(when-let [score (match-score query %)] (assoc % :score score)))
       (sort-by (juxt :score #(if (:contextual? %) 0 1)))
       vec))

(defn search [index query]
  (let [terms (words query)]
    (if (empty? terms) []
        (->> index
             (filter #(every? (fn [word] (str/includes? (:text %) word)) terms))
             (sort-by #(if (str/includes? (str/lower-case (:title %))
                                         (str/lower-case (str/trim query))) 0 1))
             (mapv #(assoc % :name (:title %) :description (:type %)
                          :action [:buffer/open (:path %)]))))))

(defn candidates [state]
  (let [{:keys [kind query]} (:minibuffer state)]
    (case kind
      :search (search (get-in state [:content :search]) query)
      :themes (match-commands query
                             (mapv (fn [[id description]]
                                     {:id id :name (name id) :description description
                                      :action [:theme/set id]})
                                   [[:system "Follow your device"] [:light "Paper & ink"] [:dark "After hours"]]))
      :buffers (match-commands
                query
                (mapv #(assoc % :name (:buffer %) :description (:title %)
                              :action [:buffer/open (:path %)])
                      (router/routes (:content state))))
      (match-commands
       query
       (mapv
        (fn [command]
          (let [record (router/record (:content state) (:route state))
                animation (get-in state [:ui :constellation])
                annotation (case (:id command)
                             :toggle-theme (str "currently " (name (theme/resolved state)))
                             :set-theme (str (name (get-in state [:theme :mode])) " mode")
                             :toggle-animation (cond (:reduced? animation) "reduced motion"
                                                     (not (:available? animation)) "still illustration"
                                                     (:paused? animation) "paused"
                                                     :else "enabled")
                             :add-to-calendar (if (and (= :meeting (get-in state [:route :id])) (meetings/calendar? record))
                                                (:title record) "open a scheduled meeting")
                             :copy-member-template "two fields are enough"
                             nil)]
            (assoc command :annotation annotation
                   :contextual? (boolean (or ((or (:contexts command) #{}) (get-in state [:route :id]))
                                             (and (= :add-to-calendar (:id command))
                                                  (= :meeting (get-in state [:route :id]))
                                                  (meetings/calendar? record)))))))
        registry)))))
