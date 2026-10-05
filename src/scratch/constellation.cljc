(ns scratch.constellation)

(def anchors
  (vec
   (for [ring (range 3)
         arm (range 6)
         :let [vertical (+ 100 (* 70 ring))
               diagonal (+ 120 (* 80 ring))
               dx (* 0.8660254038 diagonal)
               dy (* 0.5 diagonal)
               [x y] (case arm
                       0 [320 (- 280 vertical)]
                       1 [(+ 320 dx) (- 280 dy)]
                       2 [(+ 320 dx) (+ 280 dy)]
                       3 [320 (+ 280 vertical)]
                       4 [(- 320 dx) (+ 280 dy)]
                       5 [(- 320 dx) (- 280 dy)])]]
     {:arm arm :ring ring :x x :y y})))

(def slot-count (count anchors))

(defn initial [names]
  {:slots (mapv (fn [label] {:label label :incoming nil :revision 0}) (take slot-count names))
   :queue (vec (drop slot-count names))
   :cursor 0 :paused? false :reduced? false :available? false
   :visible? false :foreground? true})

(defn running? [state]
  (let [{:keys [paused? reduced? available? visible? foreground?]} (get-in state [:ui :constellation])]
    (and (= :scratch (get-in state [:route :id]))
         (not (get-in state [:minibuffer :open?]))
         available? visible? foreground? (not paused?) (not reduced?))))

(defn advance [model]
  (let [index (:cursor model)
        slot (get-in model [:slots index])
        incoming (first (:queue model))]
    (when (or (:incoming slot) (nil? incoming))
      (throw (ex-info "Package constellation cannot advance while its next slot is occupied or the queue is empty"
                      {:slot index :queue-count (count (:queue model))})))
    (-> model
        (assoc-in [:slots index :incoming] incoming)
        (update-in [:slots index :revision] inc)
        (update :queue #(vec (rest %)))
        (update :cursor #(mod (inc %) slot-count)))))

(defn commit [model index revision]
  (when-not (and (integer? index) (<= 0 index) (< index (count (:slots model)))
                 (integer? revision) (pos? revision))
    (throw (ex-info "Invalid constellation completion" {:slot index :revision revision})))
  (let [{:keys [label incoming] :as slot} (get-in model [:slots index])]
    ;; A canceled browser animation may deliver an old completion.
    (if (and incoming (= revision (:revision slot)))
      (-> model
          (assoc-in [:slots index :label] incoming)
          (assoc-in [:slots index :incoming] nil)
          (update :queue conj label))
      model)))

(defn label-size [label]
  (min 22 (/ 142 (* 0.62 (count label)))))
