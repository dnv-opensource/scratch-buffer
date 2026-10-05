(ns scratch.motion
  (:require [scratch.constellation :as constellation]
            [scratch.parentheses :as parentheses]))

(def cadence-ms 800)
(def word-cadence-ms 4800)
(def fade-ms 1200)
(defonce runtime (atom nil))

(declare sync! schedule! queue-response!)

(defn dispatch! [actions]
  ((:dispatch @runtime) actions))

(defn active? []
  (and (:node @runtime) (constellation/running? @(:app @runtime))))

(defn cycle-ms []
  (if (= :parentheses (get-in @(:app @runtime) [:ui :hero])) word-cadence-ms cadence-ms))

(defn completion [index revision]
  (if (= :word index)
    [:orbit-word/commit revision]
    [:constellation/commit index revision]))

(defn cancel! [animation]
  (when animation
    (set! (.-onfinish animation) nil)
    (.cancel animation)))

(defn animations []
  (concat [(:breath @runtime) (:response @runtime)] (:beams @runtime)
          (mapcat (fn [[_ fade]] [(:out fade) (:in fade)]) (:fades @runtime))))

(defn stop-clock! []
  (when-let [timer (:timer @runtime)]
    (js/clearTimeout timer)
    (swap! runtime assoc :timer nil
           :remaining (max 0 (- (:due @runtime) (js/performance.now))))))

(defn settle-fades! []
  (let [fades (:fades @runtime)]
    (swap! runtime assoc :fades {})
    (doseq [[_ fade] fades]
      (cancel! (:out fade))
      (cancel! (:in fade)))
    (when (seq fades)
      (dispatch! (mapv (fn [[index fade]] (completion index (:revision fade))) fades)))))

(defn schedule! [delay]
  (when (and (active?) (nil? (:timer @runtime)))
    (swap! runtime assoc
           :due (+ (js/performance.now) delay)
           :timer
           (js/setTimeout
            (fn []
              (swap! runtime assoc :timer nil :remaining (cycle-ms))
              (when (active?)
                (dispatch! [[(if (= :parentheses (get-in @(:app @runtime) [:ui :hero]))
                               :orbit-word/advance :constellation/advance)]])
                (schedule! (cycle-ms))))
            delay))))

(defn fade! [index revision]
  (let [node (:node @runtime)
        word? (= :word index)
        slot (.querySelector node (str "[data-slot='" (if word? "word" index) "']"))
        current (.querySelector slot (if word? ".orbit-word-current" ".constellation-current"))
        incoming (.querySelector slot (if word? ".orbit-word-incoming" ".constellation-incoming"))]
    (when-not (and current incoming)
      (throw (ex-info "Hero crossfade is missing a rendered label" {:slot index})))
    (let [options #js {:duration fade-ms :easing "cubic-bezier(.4,0,.2,1)" :fill "both"}
          out (.animate current #js [#js {:opacity 1} #js {:opacity 0}] options)
          in (.animate incoming #js [#js {:opacity 0} #js {:opacity 1}] options)]
      (swap! runtime assoc-in [:fades index] {:out out :in in :revision revision})
      (set! (.-onfinish in)
            (fn [_]
              (when (= revision (get-in @runtime [:fades index :revision]))
                (swap! runtime update :fades dissoc index)
                (cancel! out)
                (cancel! in)
                (dispatch! [(completion index revision)])))))))

(defn clamp [low value high]
  (max low (min high value)))

(defn respond! []
  (when (active?)
    (let [stage (:stage @runtime)
          rect (.getBoundingClientRect stage)
          progress (clamp 0 (/ (- (.-top rect)) (.-height rect)) 1)
          [x y] (:pointer @runtime)
          from (.-transform (js/getComputedStyle stage))
          target (str "perspective(850px) translate3d(" (* 5 x) "px,"
                      (+ (* 3 y) (* 6 progress)) "px,0) rotateX("
                      (- (* -12 y) (* 8 progress)) "deg) rotateY("
                      (+ (* 16 x) (* 6 progress)) "deg) rotateZ(" (* 6 progress) "deg)")]
      (cancel! (:response @runtime))
      (swap! runtime assoc :response
             (.animate stage #js [#js {:transform from} #js {:transform target}]
                       #js {:duration 650 :easing "cubic-bezier(.2,.9,.2,1.1)" :fill "both"})))))

(defn queue-response! []
  (when (and (active?) (nil? (:response-frame @runtime)))
    (swap! runtime assoc :response-frame
           (js/requestAnimationFrame
            (fn [_]
              (swap! runtime assoc :response-frame nil)
              (respond!))))))

(defn unmount! []
  (stop-clock!)
  (when-let [frame (:response-frame @runtime)] (js/cancelAnimationFrame frame))
  (when-let [observer (:observer @runtime)] (.disconnect observer))
  (when-let [stage (:stage @runtime)]
    (.removeEventListener stage "pointermove" (:pointer-move @runtime))
    (.removeEventListener stage "pointerleave" (:pointer-leave @runtime)))
  (doseq [animation (animations)] (cancel! animation))
  (swap! runtime assoc :node nil :stage nil :observer nil :breath nil :response nil :beams []
         :running? false :response-frame nil :pointer [0 0] :remaining (cycle-ms))
  (settle-fades!))

(defn mount! [node]
  (let [stage (.querySelector node ".hero-stage")
        svg (.querySelector node ".hero-svg")
        beams (if (.querySelector node ".orbital-beam")
                 (mapv (fn [{:keys [index duration] :as beam}]
                         (.animate (.querySelector node (str "[data-beam='" index "']"))
                                   (clj->js (parentheses/frames beam))
                                   #js {:duration duration :iterations js/Infinity :easing "linear"}))
                       parentheses/beams)
                 [])
        breath (when (.querySelector node ".constellation-svg")
                 (.animate svg
                           #js [#js {:transform "perspective(850px) translateZ(0) rotateX(6deg) rotateY(-10deg) rotateZ(-4deg) scale(.985)"}
                                #js {:transform "perspective(850px) translateZ(0) rotateX(-6deg) rotateY(10deg) rotateZ(4deg) scale(1.015)"}
                                #js {:transform "perspective(850px) translateZ(0) rotateX(6deg) rotateY(-10deg) rotateZ(-4deg) scale(.985)"}]
                           #js {:duration 16000 :iterations js/Infinity :easing "ease-in-out"}))
        observer (js/IntersectionObserver.
                  (fn [entries _]
                    (when (= node (:node @runtime))
                      (let [entry (aget entries 0)
                            visible? (and (.-isIntersecting entry) (>= (.-intersectionRatio entry) 0.1))]
                        (dispatch! [[:constellation/visible visible?]]))))
                  #js {:threshold #js [0 0.1]})
        pointer-move (fn [event]
                       (when (and (active?) (= "mouse" (.-pointerType event)))
                         (let [rect (.getBoundingClientRect stage)]
                           (swap! runtime assoc :pointer
                                  [(clamp -1 (- (* 2 (/ (- (.-clientX event) (.-left rect)) (.-width rect))) 1) 1)
                                   (clamp -1 (- (* 2 (/ (- (.-clientY event) (.-top rect)) (.-height rect))) 1) 1)])
                           (queue-response!))))
        pointer-leave (fn [_]
                        (swap! runtime assoc :pointer [0 0])
                        (queue-response!))]
    (when breath (.pause breath))
    (doseq [animation beams] (.pause animation))
    (swap! runtime assoc :node node :stage stage :observer observer :breath breath :beams beams
           :pointer-move pointer-move :pointer-leave pointer-leave)
    (.addEventListener stage "pointermove" pointer-move #js {:passive true})
    (.addEventListener stage "pointerleave" pointer-leave #js {:passive true})
    (.observe observer stage)
    (dispatch! [[:constellation/visible false]])))

(defn sync! []
  (when @runtime
    (let [state @(:app @runtime)
          available? (get-in state [:ui :constellation :available?])
          node (when available? (.querySelector js/document ".hero-illustration"))]
      (when (not= node (:node @runtime))
        (unmount!)
        (when node (mount! node)))
      (when node
        (let [running? (active?)
              {:keys [paused? reduced?]} (get-in @(:app @runtime) [:ui :constellation])]
          (when (not= running? (:running? @runtime))
            (swap! runtime assoc :running? running?)
            (if running?
              (do
                (doseq [animation (animations) :when animation]
                  (when (not= "finished" (.-playState animation)) (.play animation)))
                (schedule! (:remaining @runtime))
                (queue-response!))
              (do
                (stop-clock!)
                (doseq [animation (animations) :when animation] (.pause animation)))))
          (when (or paused? reduced?) (settle-fades!))
          (when reduced?
            (cancel! (:breath @runtime))
            (cancel! (:response @runtime))
            (doseq [animation (:beams @runtime)] (cancel! animation))
            (swap! runtime assoc :response nil)))))))

(defn start! [app dispatch]
  (let [supported? (and js/IntersectionObserver (.-animate (.-prototype js/Element)))
        preference (.matchMedia js/window "(prefers-reduced-motion: reduce)")]
    (reset! runtime {:app app :dispatch dispatch :node nil :fades {} :pointer [0 0]
                     :timer nil :remaining cadence-ms :running? false})
    (add-watch app :constellation-lifecycle
               (fn [_ _ before after]
                 (when (or (not= (get-in before [:route :id]) (get-in after [:route :id]))
                           (not= (get-in before [:minibuffer :open?]) (get-in after [:minibuffer :open?])))
                   (js/requestAnimationFrame (fn [_] (sync!))))))
    (.addEventListener preference "change" #(dispatch [[:constellation/reduced (.-matches %)]]))
    (.addEventListener js/document "visibilitychange"
                       #(dispatch [[:constellation/foreground (not (.-hidden js/document))]]))
    (.addEventListener js/window "scroll" queue-response! #js {:passive true})
    (.addEventListener js/window "resize" queue-response! #js {:passive true})
    (dispatch [[:constellation/reduced (.-matches preference)]
               [:constellation/foreground (not (.-hidden js/document))]
               [:constellation/available (boolean supported?)]])))
