(ns scratch.nav)

(defn start! [app]
  (let [nav (.querySelector js/document ".site-navigation")
        line (.querySelector nav ".nav-underline")
        preference (.matchMedia js/window "(prefers-reduced-motion: reduce)")]
    (when (.-animate line)
      (let [animation (atom nil)
            frame (atom nil)
            pointer-preview? (atom true)
            move! (fn []
                    (let [target (or (.querySelector nav (if @pointer-preview? "a:hover" "a:focus-visible"))
                                     (.querySelector nav "a[aria-current]"))
                          shown? (and target (pos? (.-length (.getClientRects nav))))
                          style (js/getComputedStyle line)
                          opacity (.-opacity style)
                          from (.-transform style)
                          to (if shown?
                               (let [box (.getBoundingClientRect target)
                                     parent (.getBoundingClientRect nav)
                                     padding (js/getComputedStyle target)
                                     left (js/parseFloat (.-paddingLeft padding))
                                     right (js/parseFloat (.-paddingRight padding))]
                                 (str "translate3d(" (+ (- (.-left box) (.-left parent)) left) "px,"
                                      (- (.-bottom box) (.-top parent) 6) "px,0) scaleX("
                                      (- (.-width box) left right) ")"))
                               from)]
                      (when @animation (.cancel @animation))
                      (reset! animation
                              (.animate line
                                        #js [#js {:transform (if (= "0" opacity) to from) :opacity opacity}
                                             #js {:transform to :opacity (if shown? 1 0)}]
                                        #js {:duration (if (.-matches preference) 0 200)
                                             :easing "cubic-bezier(.2,.8,.2,1)" :fill "both"}))))
            queue! (fn [& [event]]
                     (case (when event (.-type event))
                       "pointerover" (reset! pointer-preview? true)
                       "pointerleave" (reset! pointer-preview? false)
                       "focusin" (when (.matches (.-target event) ":focus-visible")
                                   (reset! pointer-preview? false))
                       nil)
                     (when-not @frame
                       (reset! frame
                               (js/requestAnimationFrame
                                (fn [_] (reset! frame nil) (move!))))))]
        (.setAttribute (.-documentElement js/document) "data-nav-motion" "true")
        (doseq [event ["pointerover" "pointerleave" "focusin" "focusout"]]
          (.addEventListener nav event queue!))
        (.addEventListener js/window "resize" queue! #js {:passive true})
        (.addEventListener preference "change" queue!)
        (when js/ResizeObserver
          (.observe (js/ResizeObserver. queue!) nav))
        (add-watch app :navigation-underline
                   (fn [_ _ before after]
                     (when (not= (get-in before [:route :path]) (get-in after [:route :path]))
                       (queue!))))
        (queue!)))))
