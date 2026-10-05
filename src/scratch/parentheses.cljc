(ns scratch.parentheses)

(def radians (/ #?(:clj Math/PI :cljs js/Math.PI) 180))
(defn sin [angle] (#?(:clj Math/sin :cljs js/Math.sin) angle))
(defn cos [angle] (#?(:clj Math/cos :cljs js/Math.cos) angle))

(def beams
  (mapv (fn [index]
          {:index index :radius (+ 120 (* 9 index))
           :tilt (+ 22 (* 7 (mod index 7))) :turn (- (* 35 index) 65)
           :phase (mod (* 137.5 index) 360)
           :tail-deg (+ 52 (* 11 (mod index 5)))
           :wave (+ 16 (* 5 (mod index 4)))
           :direction (if (even? index) 1 -1)
           :duration (+ 5800 (* 813 index))})
        (range 12)))

(def tail-stops [[0 0] [0.2 0.04] [0.45 0.18] [0.7 0.48] [1 1]])

(defn tail-start [{:keys [radius direction tail-deg]}]
  (let [angle (* (- direction) tail-deg radians)]
    [(* radius (cos angle)) (* radius (sin angle))]))

(defn tail-path [{:keys [radius direction] :as beam}]
  (let [[x y] (tail-start beam)]
    (str "M" x " " y " A" radius " " radius " 0 0 " (if (pos? direction) 1 0) " " radius " 0")))

(defn plane [{:keys [tilt turn]}]
  (str "rotate(" turn ") scale(1 "
       (cos (* tilt radians)) ")"))

(defn angle [{:keys [phase direction wave]} progress]
  (+ phase (* direction (+ (* 360 progress)
                           (* wave (- (sin (* (+ (* 360 progress) phase) radians))
                                      (sin (* phase radians))))))))

(defn frames [beam]
  (mapv (fn [step]
          (let [progress (/ step 48)
                theta (angle beam progress)]
            {:offset progress
             :transform (str "translateZ(0) rotate(" theta "deg)")
             :opacity (+ 0.38 (* 0.55 (/ (inc (sin (* theta radians))) 2)))}))
        (range 49)))

(def initial-word {:index 0 :incoming nil :revision 0})

(defn advance-word [model names]
  (when (or (:incoming model) (empty? names))
    (throw (ex-info "Orbital word cannot advance during a crossfade or without names" {})))
  (-> model
      (assoc :incoming (mod (inc (:index model)) (count names)))
      (update :revision inc)))

(defn commit-word [model revision]
  (when-not (and (integer? revision) (pos? revision))
    (throw (ex-info "Invalid orbital word completion" {:revision revision})))
  (if (and (:incoming model) (= revision (:revision model)))
    (assoc model :index (:incoming model) :incoming nil)
    model))
