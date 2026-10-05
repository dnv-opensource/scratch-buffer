(ns scratch.state
  (:require [clojure.string :as str]
            [scratch.commands :as commands]
            [scratch.constellation :as constellation]
            [scratch.github :as github]
            [scratch.meetings :as meetings]
            [scratch.parentheses :as parentheses]
            [scratch.theme :as theme]
            [scratch.router :as router]))

(def theme-modes #{:system :light :dark})

(defn initial [data base path]
  (let [route (router/resolve-route data path)]
    {:base base :route route
     :buffer {:current (:buffer route) :previous nil}
     :navigation {:positions {}}
     :minibuffer {:open? false :kind :commands :query "" :selection 0 :history [] :history-index -1 :draft ""}
     :theme {:mode :system :system-dark? false}
     :content data :github {:status :idle :snapshot nil :error nil :revision 0}
     :ui {:scroll "All" :notice nil :tip nil :tip-notice nil :tip-revision 0 :meeting-times {} :hero :constellation
          :constellation (constellation/initial (get-in data [:constellation :names]))
          :orbit-word parentheses/initial-word}
     :pwa {:offline? false :ready? false :installable? false}}))

(def resolved-theme theme/resolved)

(defn member-template [state]
  (str "{:github \"your-github-handle\"\n :joined \"" (subs (get-in state [:content :today]) 0 7) "\"}"))

(defn result [state & effects]
  {:state state :effects (vec effects)})

(defn transition [state [action & args]]
  (let [[value options] args]
    (case action
      :buffer/open
      (let [destination (router/destination value)
            path (router/normalize-path destination)
            anchor (second (str/split destination #"#" 2))
            route (router/resolve-route (:content state) path)
            old-path (get-in state [:route :path])
            positions (assoc (get-in state [:navigation :positions]) old-path (or (:scroll options) 0))
            scroll (if (:restore? options) (get positions path 0) 0)
            next-state (-> state
                           (assoc :route route)
                           (assoc-in [:navigation :positions] positions)
                           (assoc-in [:buffer :current] (:buffer route))
                           (cond-> (not= old-path path) (assoc-in [:buffer :previous] old-path))
                           (assoc-in [:minibuffer :open?] false))]
        (result next-state
                [:history/navigate (router/url (:base state) destination) options]
                [:document/title (:title route)]
                [:dispatch [:github/load-snapshot]]
                [:navigation/settle scroll anchor]))

      :buffer/previous
      (if-let [path (get-in state [:buffer :previous])]
        (result state [:dispatch [:buffer/open path {:restore? true}]])
        (result (assoc-in state [:ui :notice] "This is your first buffer. Choose a page with the buffer switcher.")))

      :minibuffer/open
      (result (-> state
                  (assoc-in [:minibuffer :open?] true)
                  (assoc-in [:minibuffer :kind] (or value :commands))
                  (assoc-in [:minibuffer :query] "")
                  (assoc-in [:minibuffer :selection] 0)
                  (assoc-in [:minibuffer :history-index] -1)
                  (assoc-in [:minibuffer :draft] ""))
              [:focus/minibuffer])

      :minibuffer/cancel
      (result (assoc-in state [:minibuffer :open?] false) [:focus/return])

      :minibuffer/input
      (result (-> state
                  (assoc-in [:minibuffer :query] value)
                  (assoc-in [:minibuffer :selection] 0)
                  (assoc-in [:minibuffer :history-index] -1)))

      :minibuffer/move
      (let [n (count (commands/candidates state))
            selected (get-in state [:minibuffer :selection])]
        (result (assoc-in state [:minibuffer :selection] (if (pos? n) (mod (+ selected value) n) 0))
                [:completion/scroll]))

      :minibuffer/select
      (result (assoc-in state [:minibuffer :selection] value))

      :minibuffer/history
      (let [{:keys [history history-index query draft]} (:minibuffer state)
            index (max -1 (min (dec (count history)) (+ history-index value)))]
        (result (-> state
                    (cond-> (= history-index -1) (assoc-in [:minibuffer :draft] query))
                    (assoc-in [:minibuffer :history-index] index)
                    (assoc-in [:minibuffer :query] (if (= index -1) draft (get history index "")))
                    (assoc-in [:minibuffer :selection] 0))))

      :minibuffer/execute
      (if-let [candidate (get (commands/candidates state)
                             (get-in state [:minibuffer :selection]))]
        (result state [:dispatch [:command/execute candidate]])
        (result (assoc-in state [:ui :notice] "No matching result. Try a different query.")))

      :command/execute
      (let [candidate (if (map? value) value (some #(when (= value (:id %)) %) commands/registry))]
        (if candidate
          (result (-> state
                      (assoc-in [:minibuffer :open?] false)
                      (update-in [:minibuffer :history]
                                 #(vec (take 30 (cons (:name candidate) (remove #{(:name candidate)} %))))))
                  [:focus/return]
                  [:dispatch (:action candidate)])
          (throw (ex-info "Unknown command" {:id value}))))

      :theme/set
      (if (theme-modes value)
        (result (assoc-in state [:theme :mode] value) [:theme/apply] [:theme/persist value])
        (throw (ex-info "Invalid theme mode" {:mode value})))

      :theme/toggle
      (result state [:dispatch [:theme/set (if (= :dark (resolved-theme state)) :light :dark)]])

      :theme/system
      (result (assoc-in state [:theme :system-dark?] value) [:theme/apply])

      :theme/restore
      (if (theme-modes value)
        (result (assoc-in state [:theme :mode] value) [:theme/apply])
        (result (assoc-in state [:ui :notice] "The saved theme was invalid. Using your device theme.")
                [:theme/apply]))

      :github/open
      (if (get-in state [:pwa :offline?])
        (result (assoc-in state [:ui :notice] "You are offline. Published buffers are cached; GitHub Discussions need a connection."))
        (result state [:external/open (github/discussion-url state value)]))

      :github/load-snapshot
      (if (and (github/readable? state)
               (or (= :idle (get-in state [:github :status]))
                   (and value (not= :loading (get-in state [:github :status])))))
        (let [revision (inc (get-in state [:github :revision]))]
          (result (-> state
                      (assoc-in [:github :status] :loading)
                      (assoc-in [:github :error] nil)
                      (assoc-in [:github :revision] revision))
                  [:github/fetch-snapshot (router/url (:base state) github/snapshot-path) revision]))
        (result state))

      :github/snapshot-loaded
      (if (= value (get-in state [:github :revision]))
        (result (-> state
                    (assoc-in [:github :status] :ready)
                    (assoc-in [:github :error] nil)
                    (assoc-in [:github :snapshot]
                              (github/validate-snapshot! (get-in state [:content :site :repository]) options))))
        (result state))

      :github/snapshot-failed
      (if (= value (get-in state [:github :revision]))
        (do
          (when-not (github/text? options) (throw (ex-info "Missing discussion error message." {})))
          (result (-> state
                      (assoc-in [:github :status] :error)
                      (assoc-in [:github :error] options))))
        (result state))

      :teams/open
      (if (get-in state [:pwa :offline?])
        (result (assoc-in state [:ui :notice] "You are offline. Teams chat needs a connection."))
        (result state [:external/open-tab (get-in state [:content :site :teams-url])]))

      :member/copy-template
      (result state [:clipboard/write (member-template state) :member-template])

      :code/copy
      (do
        (when-not (and (string? value) (not (str/blank? value)))
          (throw (ex-info "Code to copy must be nonempty text" {})))
        (result state [:clipboard/write value :code]))

      :meetings/local-times
      (result (assoc-in state [:ui :meeting-times] value))

      :meetings/prepare (result state [:meetings/format-local])

      :meeting/download
      (let [record (router/record (:content state) (:route state))]
        (if (and (= :meeting (get-in state [:route :id])) (meetings/calendar? record))
          (result state [:download/file (router/url (:base state) (meetings/calendar-path record))
                         (str (:id record) ".ics")])
          (result (assoc-in state [:ui :notice] "Open a scheduled meeting to add it to your calendar. Example meetings are not invitations."))))
      :tip/random
      (if (seq (filter :tip? (get-in state [:content :snippets])))
        (result state [:tip/choose])
        (result (assoc-in state [:ui :notice] "No tips have been published yet.")))

      :tip/set
      (let [tip (get (vec (filter :tip? (get-in state [:content :snippets]))) value)
            revision (inc (get-in state [:ui :tip-revision]))]
        (when-not tip (throw (ex-info "Unknown tip" {:index value})))
        (result (-> state
                    (assoc-in [:ui :tip] value)
                    (assoc-in [:ui :tip-revision] revision)
                    (assoc-in [:ui :tip-notice] tip))
                [:tip/expire-later revision]))

      :tip/expire
      (result (if (= value (get-in state [:ui :tip-revision]))
                (assoc-in state [:ui :tip-notice] nil)
                state))

      :tip/dismiss
      (result (assoc-in state [:ui :tip-notice] nil) [:tip/cancel-expiry] [:tip/return-focus])

      :tip/pause (result state [:tip/cancel-expiry])

      :tip/resume
      (if (get-in state [:ui :tip-notice])
        (result state [:tip/expire-later (get-in state [:ui :tip-revision])])
        (result state))

      :ui/scroll (result (assoc-in state [:ui :scroll] value))
      :hero/select
      (if (#{:constellation :parentheses} value)
        (result (assoc-in state [:ui :hero] value) [:constellation/sync])
        (throw (ex-info "Unknown home illustration" {:variant value})))
      :constellation/advance
      (let [index (get-in state [:ui :constellation :cursor])
            next-state (update-in state [:ui :constellation] constellation/advance)
            revision (get-in next-state [:ui :constellation :slots index :revision])]
        (result next-state [:constellation/fade index revision]))

      :constellation/commit
      (result (update-in state [:ui :constellation] #(constellation/commit % value options)))

      :orbit-word/advance
      (let [next-state (update-in state [:ui :orbit-word]
                                  #(parentheses/advance-word % (get-in state [:content :constellation :names])))]
        (result next-state [:orbit-word/fade (get-in next-state [:ui :orbit-word :revision])]))

      :orbit-word/commit
      (result (update-in state [:ui :orbit-word] #(parentheses/commit-word % value)))

      :constellation/toggle
      (let [next-state (update-in state [:ui :constellation :paused?] not)
            {:keys [paused? reduced? available?]} (get-in next-state [:ui :constellation])]
        (result (assoc-in next-state [:ui :notice]
                          (cond
                            reduced? "Reduced motion is enabled. The home illustration stays still."
                            (not available?) "This browser shows a still home illustration."
                            paused? "Home illustration paused."
                            :else "Home illustration resumed."))
                [:constellation/sync]))

      :constellation/reduced
      (result (assoc-in state [:ui :constellation :reduced?] value) [:constellation/sync])

      :constellation/available
      (result (assoc-in state [:ui :constellation :available?] value) [:constellation/sync])

      :constellation/visible
      (result (assoc-in state [:ui :constellation :visible?] value) [:constellation/sync])

      :constellation/foreground
      (result (assoc-in state [:ui :constellation :foreground?] value) [:constellation/sync])

      :ui/notice (result (assoc-in state [:ui :notice] value))
      :ui/dismiss (result (assoc-in state [:ui :notice] nil))
      :pwa/offline (result (assoc-in state [:pwa :offline?] value))
      :pwa/ready (result (assoc-in state [:pwa :ready?] true))
      :pwa/available (result (assoc-in state [:pwa :installable?] value))
      :pwa/install (result state [:pwa/prompt])
      (throw (ex-info "Unknown action" {:action action})))))
