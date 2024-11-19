(ns fulcro.inspect.ui.data-viewer
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro-css.localized-dom :as dom]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.application :as app]
            [com.fulcrologic.fulcro.components :as fp]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.mutations :as mutations]
            [fulcro.inspect.helpers :as h]
            [fulcro.inspect.helpers.clipboard :as clip]
            [fulcro.inspect.lib.history :as hist]
            [fulcro.inspect.ui.core :as ui]
            [fulcro.inspect.ui.effects :as effects]
            [fulcro.inspect.ui.events :as events]
            [goog.object :as gobj]
            [taoensso.timbre :as log]))

(declare DataViewer)

(def vec-max-inline 2)
(def sequential-max-inline 5)
(def map-max-inline 10)

(defn pprint-str [x]
  (with-out-str
    (cljs.pprint/pprint x)))

(defn children-expandable-paths [x]
  (loop [lookup [{:e x :p []}]
         paths  []]
    (if (seq lookup)
      (let [[{:keys [e p]} & t] lookup]
        (cond
          (or (sequential? e) (set? e))
          (let [sub-paths (keep-indexed (fn [i x] (if (coll? x) {:e x :p (conj p i)})) e)]
            (recur (into [] (concat t sub-paths))
              (into paths (map :p) sub-paths)))

          (map? e)
          (let [sub-paths (keep (fn [[k x]] (if (coll? x) {:e x :p (conj p k)})) e)]
            (recur (into [] (concat t sub-paths))
              (into paths (map :p) sub-paths)))

          :else
          (recur t paths)))
      paths)))

(mutations/defmutation toggle [{::keys [path propagate?]}]
  (action [env]
    (let [{:keys [state ref]} env
          open?   (get-in @state (conj ref ::expanded path))
          content (get-in @state (concat ref [::content] path))
          toggled (not open?)
          paths   (cond-> {path toggled}
                    propagate? (into (map #(vector (into path %) toggled)) (children-expandable-paths
                                                                             content)))]
      (swap! state update-in ref update ::expanded merge paths))))

(defn keyable? [x]
  (or (nil? x)
    (string? x)
    (keyword? x)
    (number? x)
    (boolean? x)
    (symbol? x)
    (uuid? x)
    (tempid/tempid? x)
    (and (vector? x)
      (<= (count x) vec-max-inline))))

(declare render-data)

(defn render-ordered-list [{:keys [css path path-action linkable?] :as input} content]
  (for [[x i] (map vector content (range))]
    (dom/div #js {:key i :className (:list-item css)}
      (dom/div #js {:className (:list-item-index css)}
        (if (and linkable? path-action)
          (dom/div #js {:className (:path-action css)
                        :onClick   #(path-action (conj path i))}
            (str i))
          (str i)))
      (render-data (update input :path conj i) x))))

(defn render-sequential [{:keys [css search expanded path toggle open-close static?] :as input} content]
  (dom/div #js {:className (:data-row css)}
    (if (and (not static?) (> (count content) vec-max-inline))
      (dom/div #js {:className   (:toggle-button css)
                    :onMouseDown events/stop-event
                    :onClick     #(toggle % path)}
        (if (expanded path)
          ui/arrow-down
          ui/arrow-right)

        (if (expanded path)
          (dom/div {:className (:copy-button css)
                    :onClick   #(do
                                  (events/stop-event %)
                                  (clip/copy-to-clipboard (pprint-str content))
                                  (effects/animate-text-out (gobj/get % "target") "Copied"))} (ui/icon :content_copy)))))

    (cond
      (expanded path)
      (dom/div #js {:className (:list-container css)}
        (render-ordered-list input content))

      :else
      (dom/div #js {:className (:list-inline css)}
        (first open-close)
        (for [[x i] (map vector (take sequential-max-inline content) (range))]
          (dom/div #js {:key i :className (:list-inline-item css)}
            (render-data (update input :path conj i) x)))
        (if (> (count content) sequential-max-inline)
          (dom/div #js {:className (:list-inline-item css)} "..."))
        (second open-close)))))

(defn render-vector [input content]
  (render-sequential (assoc input :open-close ["[" "]"] :linkable? true) content))

(defn render-list [input content]
  (render-sequential (assoc input :open-close ["(" ")"] :linkable? true) content))

(defn render-set [input content]
  (render-sequential (assoc input :open-close ["#{" "}"]) content))

(defn render-map [{:keys [css search expanded path toggle path-action elide-one? static?] :as input} content]
  (dom/div #js {:className (:data-row css)}
    (if (and (not static?)
          (or (not elide-one?)
            (> 1 (count content))))
      (dom/div #js {:onMouseDown events/stop-event
                    :onClick     #(if (events/shift-key? %)
                                    (do
                                      (events/stop-event %)
                                      (clip/copy-to-clipboard (pprint-str content))
                                      (effects/animate-text-out (gobj/get % "target") "Copied"))
                                    (toggle % path))
                    :className   (:toggle-button css)}
        (if (expanded path)
          ui/arrow-down
          ui/arrow-right)
        (if (expanded path)
          (dom/div {:className (:copy-button css)
                    :onClick   #(do
                                  (events/stop-event %)
                                  (clip/copy-to-clipboard (pprint-str content))
                                  (effects/animate-text-out (gobj/get % "target") "Copied"))} (ui/icon :content_copy)))))

    (cond
      (empty? content)
      "{}"

      (expanded path)
      (if (every? keyable? (keys content))
        (dom/div #js {:className (:map-container css)}
          (->> content
            (sort-by (comp str first))
            (mapv (fn [[k v]]
                    (if (expanded (conj path k))
                      [(dom/div #js {:key (str k "-key")}
                         (dom/div #js {:className (:list-item-index css)}
                           (if path-action
                             (dom/div #js {:className (:path-action css)
                                           :onClick   #(path-action (conj path k))}
                               (render-data input k))
                             (render-data input k))))
                       (dom/div #js {:key (str k "-key-space")})
                       (dom/div #js {:className (:map-expanded-item css)
                                     :key       (str k "-value")} (render-data (update input :path conj k) v))]
                      [(dom/div #js {:key (str k "-key")}
                         (dom/div #js {:className (:list-item-index css)}
                           (if path-action
                             (dom/div #js {:className (:path-action css)
                                           :onClick   #(path-action (conj path k))}
                               (render-data input k))
                             (render-data input k))))
                       (dom/div #js {:key (str k "-value")} (render-data (update input :path conj k) v))])))
            (apply concat)))

        (dom/div #js {:className (:list-container css)}
          (render-ordered-list input content)))

      (or (expanded (vec (butlast path)))
        (empty? path))
      (dom/div #js {:className (:list-inline css)}
        "{"
        (->> content
          (sort-by (comp str first))
          (take map-max-inline)
          (mapv (fn [[k v]]
                  [(dom/div #js {:className (:map-inline-key-item css) :key (str k "-key")} (render-data input k))
                   (dom/div #js {:className (:map-inline-value-item css) :key (str k "-value")} (render-data (update input :path conj k) v))]))
          (interpose ", ")
          (apply concat))
        (if (> (count content) map-max-inline)
          ", ")
        (if (> (count content) map-max-inline)
          (dom/div #js {:className (:list-inline-item css)} "..."))
        "}")

      :else
      "{...}")))

(defn matches? [s search]
  (str s)
  (and (string? s) (seq s) (string? search) (seq search) (str/includes? (str/lower-case s) (str/lower-case search))))

(defn highlight
  "Emit DOM for highlighting the given search string if it is contained withing the string s. Otherwise returns s."
  [s search]
  (if (matches? s search)
    (dom/span {:style {:backgroundColor "yellow"}} s)
    s))

(defn paths-that-match
  "Returns a vector of paths that match the given search string in data. The retuned list of paths will be prefixed with
  path-to-here."
  [path-to-here data search]
  (cond
    (map? data) (reduce
                  (fn [result k]
                    (let [new-path (conj path-to-here k)
                          p        (paths-that-match new-path (get data k) search)]
                      (if (seq p)
                        (concat result p)
                        result)))
                  []
                  (keys data))
    (or (vector? data) (list? data)) (reduce
                                       (fn [result [idx d]]
                                         (let [new-path (conj path-to-here idx)
                                               p        (paths-that-match new-path d search)]
                                           (if (seq p)
                                             (concat result p)
                                             result)))
                                       []
                                       (map-indexed (fn [i d] [i d]) data))
    (set? data) (if (some #(matches? (str %) search) data) [path-to-here] [])
    (and (string? data) (matches? data search)) [path-to-here]
    (and data (seq (str data)) (matches? (str data) search)) [path-to-here]
    :otherwise []))

(defn render-data [{:keys [css search] :as input} content]
  (let [input (update input :expanded #(or % {}))]
    (cond
      (nil? content)
      (dom/div #js {:className (:nil css)} "nil")

      (string? content)
      (dom/div #js {:className (:string css)} (highlight (pr-str content) search))

      (keyword? content)
      (dom/div #js {:className (:keyword css)} (highlight (str content) search))

      (symbol? content)
      (dom/div #js {:className (:symbol css)} (highlight (str content) search))

      (number? content)
      (dom/div #js {:className (:number css)} (highlight (str content) search))

      (boolean? content)
      (dom/div #js {:className (:boolean css)} (highlight (str content) search))

      (uuid? content)
      (dom/div #js {:className (:uuid css)} "#uuid " (dom/span {:className (:string css)} (highlight
                                                                                            (str "\"" content "\"")
                                                                                            search)))

      (map? content)
      (render-map input content)

      (vector? content)
      (render-vector input content)

      (list? content)
      (render-list input content)

      (set? content)
      (render-set input content)

      :else
      (dom/div #js {:className (:unknown css)} (highlight (str content) search)))))

(fp/defsc DataViewer
  [this
   {::keys [content expanded elide-one? static?] :as props}
   {:keys  [allow-stale? raw?]
    ::keys [path-action search on-expand-change path]}
   css]
  {:initial-state (fn [content] {::id       (random-uuid)
                                 ::content  content
                                 ::expanded {}})
   :pre-merge     (fn [{:keys [current-normalized data-tree]}]
                    (merge {::id       (random-uuid)
                            ::expanded {}}
                      current-normalized data-tree))
   :ident         [::id ::id]
   :query         [::id ::content ::expanded ::elide-one? ::static?
                   [::app/active-remotes '_]]
   :css           [[:.container ui/css-code-font]
                   [:.nil {:color "#808080"}]
                   [:.string {:color "#c41a16"}]
                   [:.keyword {:color "#881391"}]
                   [:.symbol {:color "#134f91"}]
                   [:.number {:color "#1c00cf"}]
                   [:.boolean {:color "#009999"}]

                   [:.data-row {:display     "flex"
                                :margin-left "3px"}]

                   [:.list-inline {:display "flex"}]
                   [:.list-inline-item {:margin "0 4px"}]

                   [:.list-container {:padding          "3px 12px"
                                      :border-top       "2px solid rgba(60, 90, 60, 0.1)"
                                      :margin           "0px 1px 1px"
                                      :background-color "rgba(100, 255, 100, 0.08)"}]

                   [:.toggle-button ui/css-triangle
                    [:svg
                     {:width      "14px"
                      :margin-top "-5px"}]]

                   [:.copy-button
                    {:margin-top "3px"
                     :opacity    "0.4"
                     :transition "all 200ms"}
                    [:&:hover
                     {:opacity "0.7"}]]

                   [:.list-item {:display     "flex"
                                 :align-items "flex-start"}]
                   [:.list-item-index {:background    "#dddddd"
                                       :border-right  "2px solid rgba(100, 100, 100, 0.2)"
                                       :min-width     "35px"
                                       :margin-bottom "1px"
                                       :margin-right  "5px"
                                       :padding       "0 3px"}]

                   [:.map-container {:padding               "3px 12px"
                                     :border-top            "2px solid rgba(60, 90, 60, 0.1)"
                                     :margin                "0px 1px 1px"
                                     :background-color      "rgba(100, 255, 100, 0.08)"

                                     :display               "grid"
                                     :grid-template-columns "max-content 1fr"}]

                   [:.map-expanded-item {:grid-column-start "1"
                                         :grid-column-end   "3"}]

                   [:.map-inline-key-item {:margin-left "4px"}]
                   [:.map-inline-value-item {:margin-left "8px"}]

                   [:.path-action {:cursor "pointer"}
                    [:&:hover
                     [:div {:text-decoration "underline"}]]]]}
  (let [{:keys [id]} content
        app-uuid      (h/current-app-uuid (fp/component->state-map this))
        last-step     (hist/closest-populated-history-step this id)
        desired-state (when-not raw? (hist/state-map-for-id this app-uuid id))
        stale?        (not= (:id last-step) id)
        [actual-revision base-data] (cond
                                      desired-state [id desired-state]
                                      allow-stale? [(:id last-step) (:value last-step)]
                                      :else [id {}])
        data            (cond-> base-data
                          (vector? path) (get-in path))]
    (dom/div :.container
      (when-not (or path raw?)
        (dom/h4 {:style {:marginTop    "2px"
                         :marginBottom "2px"}}
          (cond
            (not stale?) (str "Revision " id)
            (and stale? (not allow-stale?)) (str "Revision " id " has not been fetched from the application.")
            :else (if (and (int? actual-revision) (int? id))
                    (str "Stale Revision " actual-revision " (waiting on revision " id ")")
                    ""))))
      (if (and stale? (not allow-stale?) (not raw?))
        (when-not path
          (dom/button {:onClick #(fp/transact! this `[(hist/remote-fetch-history-step ~{:id id})])} "Fetch Now"))
        (render-data {:expanded    expanded
                      :static?     static?
                      :search      search
                      :elide-one?  elide-one?
                      :toggle      #(do
                                      (fp/transact! this [`(toggle {::path       ~%2
                                                                    ::propagate? ~(or (.-altKey %)
                                                                                    (.-metaKey %))})])
                                      (if on-expand-change
                                        (on-expand-change %2
                                          (-> this (app/current-state)
                                            (get-in (conj (fp/get-ident this) ::expanded))))))
                      :css         css
                      :path        []
                      :path-action path-action}
          (if raw? content data))))))

(defn all-subvecs [v]
  (:result
    (reduce
      (fn [{:keys [last result] :as acc} i]
        (assoc acc :last (conj last i) :result (conj result (conj last i))))
      {:last [] :result []} v)))

(mutations/defmutation search-expand [{:keys [viewer search]}]
  (action [{:keys [state]}]
    (let [data-view-ident (fp/get-ident DataViewer viewer)
          expanded-path   (conj data-view-ident ::expanded)]
      (swap! state update-in expanded-path
        (fn [old paths] (reduce (fn [acc p]
                                  (if (> (count p) 1)
                                    (reduce
                                      (fn [acc2 subpath]
                                        (assoc acc2 subpath true))
                                      acc
                                      (all-subvecs (butlast p)))
                                    acc)) old paths))
        (paths-that-match [] (::content viewer) search)))))

(let [factory (fp/factory DataViewer)]
  (defn data-viewer [props & [computed]]
    (factory (fp/computed props computed))))
