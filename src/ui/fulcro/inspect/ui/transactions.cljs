(ns fulcro.inspect.ui.transactions
  (:require
    [fulcro.inspect.lib.diff :as diff]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro-css.css :as css]
    [com.fulcrologic.fulcro-css.localized-dom :as dom]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.components :as fp]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.fulcro.mutations :as mutations :refer-macros [defmutation]]
    [fulcro.inspect.helpers :as h]
    [fulcro.inspect.lib.history :as hist]
    [fulcro.inspect.ui.core :as ui]
    [fulcro.inspect.ui.data-viewer :as data-viewer]
    [taoensso.timbre :as log]))

(def tx-options :com.fulcrologic.fulcro.algorithms.tx-processing/options)

(declare TransactionRow)

(defn format-data [event-data]
  (let [data           (or event-data {})
        {:keys [tx-data]} (css/get-classnames TransactionRow)
        formatted-data (pr-str data)
        result         (if (> (count formatted-data) 120)
                         (dom/pre {:className tx-data} (with-out-str (pprint data)))
                         (dom/pre {:className tx-data} formatted-data))]
    result))

(defmulti format-tx (fn [tx] (first tx)))

(defmethod format-tx :default [tx] (format-data tx))
(defmethod format-tx 'com.fulcrologic.fulcro.ui-state-machines/begin [tx]
  (let [args       (second tx)
        {:com.fulcrologic.fulcro.ui-state-machines/keys [asm-id event-data]} args
        event-data (dissoc event-data :error-timeout :deferred-timeout)]
    (dom/div
      (dom/u "State Machine (BEGIN)")
      (dom/b (str asm-id))
      ent/nbsp
      (format-data event-data))))

(defmethod format-tx 'com.fulcrologic.fulcro.ui-state-machines/trigger-state-machine-event [tx]
  (let [args       (second tx)
        {:com.fulcrologic.fulcro.ui-state-machines/keys [asm-id event-id event-data]} args
        event-data (or (dissoc event-data :error-timeout :deferred-timeout) {})]
    (dom/div
      (dom/u "State Machine")
      (dom/b "(" (str asm-id) ")")
      ent/nbsp
      (dom/b (str event-id))
      ent/nbsp
      (format-data event-data))))

(defmethod format-tx 'com.fulcrologic.fulcro.data-fetch/internal-load! [tx]
  (let [args (second tx)
        {:keys [query]} args]
    (dom/div
      (dom/u "LOAD")
      ent/nbsp
      ent/nbsp
      (format-data query))))

(fp/defsc TxPrinter
  [this {::keys [content]}]
  {}
  (format-tx content))

(def tx-printer (fp/factory TxPrinter))

(fp/defsc TransactionRow
  [this
   {:ui/keys             [compress-count]
    :fulcro.history/keys [client-time tx]
    :as                  props}
   {::keys [on-select selected? on-replay]}]

  {:initial-state (fn [transaction]
                    (merge {::tx-id                     (random-uuid)
                            :fulcro.history/client-time (js/Date.)
                            :ui/compress-count          0}
                      transaction))
   :pre-merge     (fn [{:keys [current-normalized data-tree]}]
                    (merge {::tx-id                     (random-uuid)
                            :fulcro.history/client-time (js/Date.)
                            :ui/compress-count          0}
                      current-normalized data-tree))
   :ident         [::tx-id ::tx-id]
   :query         [::tx-id
                   :fulcro.history/client-time :fulcro.history/tx
                   :fulcro.history/db-before :fulcro.history/db-after
                   :fulcro.history/diff
                   :fulcro.history/network-sends :ident-ref :component
                   :ui/compress-count]
   :css           [[:.tx-data {:font-size "9pt"
                               :font      "monospace"
                               :margin    0}]
                   [:.container {:display       "flex"
                                 :cursor        "pointer"
                                 :flex          "1"
                                 :border-bottom "1px solid #eee"
                                 :align-items   "center"
                                 :padding       "5px 0"}
                    [:.icon {:display "none"}]
                    [:&:hover {:background ui/color-row-hover}
                     [:.icon {:display "block"}]]
                    [:&.selected {:background ui/color-row-selected}]]
                   [:.compress-count {:color         "#fff"
                                      :font-family   ui/mono-font-family
                                      :font-size     "13px"
                                      :background    "#6D84AF"
                                      :padding       "0px 3px"
                                      :margin        "0 3px 0 2px"
                                      :border-radius "5px"
                                      :font-weight   "bold"}]
                   [:.data-container {:flex       1
                                      :max-height "100px"
                                      :overflow   "auto"}]
                   [:.icon {:margin "-5px 6px"}
                    [:$c-icon {:fill      ui/color-icon-normal
                               :transform "scale(0.7)"}
                     [:&:hover {:fill ui/color-icon-strong}]]]
                   [:.timestamp ui/css-timestamp]]
   :css-include   [data-viewer/DataViewer]}
  (dom/div :.container {:classes [(if selected? :.selected)]
                        :onClick #(if on-select (on-select props))}
    (dom/div :.timestamp (ui/print-timestamp client-time))
    (if (pos? compress-count)
      (dom/div :.compress-count (str compress-count)))
    (dom/div :.data-container (tx-printer {::content tx}))
    (if on-replay
      (dom/div :.icon {:onClick #(do
                                   (.stopPropagation %)
                                   (on-replay props))}
        (ui/icon {:title "Replay mutation"} :refresh)))))

(def transaction-row (fp/computed-factory TransactionRow {:keyfn ::tx-id}))

(defn merge-current [{:keys [current-normalized data-tree]} k]
  (or (get data-tree k) (get current-normalized k)))

(declare Transaction)

(defmutation compute-diff [tx]
  (action [{:keys [app state] :as env}]
    (tap> tx)
    (let [tx-ref    (fp/ident Transaction tx)
          old-state (get-in tx [:ui/old-state-view ::data-viewer/content] {})
          app-uuid  (h/current-app-uuid @state)
          new-state (get-in tx [:ui/new-state-view ::data-viewer/content] {})
          ;; TASK: The db versions for the transaction are incorrect
          old       (hist/state-map-for-id app app-uuid (log/spy :info (:id old-state)))
          new       (hist/state-map-for-id app app-uuid (log/spy :info (:id new-state)))
          {::diff/keys [updates removals]} (log/spy :info (diff/diff new old))
          env'      (assoc env :ref tx-ref)]
      (when updates
        (h/create-entity! env' data-viewer/DataViewer updates :set :ui/diff-add-view))
      (when removals
        (h/create-entity! env' data-viewer/DataViewer removals :set :ui/diff-rem-view))
      (swap! state update-in tx-ref assoc :ui/diff-computed? true))))


(fp/defsc Transaction
  [this {:keys                [ident-ref component]
         :fulcro.history/keys [network-sends]
         :ui/keys             [tx-view sends-view
                               old-state-view new-state-view
                               diff-add-view diff-rem-view]
         :as                  props}]
  {:initial-state
   (fn [{:fulcro.history/keys [tx network-sends db-before db-after]
         :as                  transaction}]
     (merge {::tx-id (random-uuid)}
       transaction
       {:ui/tx-view        (-> (fp/get-initial-state data-viewer/DataViewer tx)
                             (assoc ::data-viewer/expanded {[] true}))
        :ui/sends-view     (fp/get-initial-state data-viewer/DataViewer network-sends)
        :ui/old-state-view (fp/get-initial-state data-viewer/DataViewer db-before)
        :ui/new-state-view (fp/get-initial-state data-viewer/DataViewer db-after)
        :ui/diff-add-view  (fp/get-initial-state data-viewer/DataViewer nil)
        :ui/diff-rem-view  (fp/get-initial-state data-viewer/DataViewer nil)
        :ui/full-computed? true}))

   :pre-merge
   (fn [{:keys [current-normalized data-tree] :as m}]
     (let [tx            (merge-current m :fulcro.history/tx)
           network-sends (merge-current m :fulcro.history/network-sends)
           db-before     (merge-current m :fulcro.history/db-before)
           db-after      (merge-current m :fulcro.history/db-after)]
       (merge {::tx-id (random-uuid)}
         {:ui/tx-view        {::data-viewer/content  tx
                              ::data-viewer/expanded {[] true}}
          :ui/sends-view     {::data-viewer/content network-sends}
          :ui/old-state-view {::data-viewer/content db-before}
          :ui/new-state-view {::data-viewer/content db-after}
          :ui/diff-add-view  {::data-viewer/content nil}
          :ui/diff-rem-view  {::data-viewer/content nil}
          :ui/full-computed? true}
         current-normalized data-tree)))

   :ident
   [::tx-id ::tx-id]

   :query
   [::tx-id ::timestamp
    :fulcro.history/client-time :fulcro.history/tx
    :fulcro.history/db-before :fulcro.history/db-after
    :fulcro.history/diff
    :fulcro.history/network-sends :ident-ref :component
    :ui/full-computed?
    {:ui/tx-view (fp/get-query data-viewer/DataViewer)}
    {:ui/sends-view (fp/get-query data-viewer/DataViewer)}
    {:ui/old-state-view (fp/get-query data-viewer/DataViewer)}
    {:ui/new-state-view (fp/get-query data-viewer/DataViewer)}
    {:ui/diff-add-view (fp/get-query data-viewer/DataViewer)}
    {:ui/diff-rem-view (fp/get-query data-viewer/DataViewer)}]

   :css
   [[:.container {:height "100%"}]]

   :css-include
   [data-viewer/DataViewer]}
  (let [css (css/get-classnames Transaction)]
    (dom/div #js {:className (:container css)}
      (ui/info {::ui/title "Ref"}
        (ui/ident {} ident-ref))

      (ui/info {::ui/title "Transaction"}
        (dom/pre {:style {:fontSize "10pt"}}
          (with-out-str (pprint (::data-viewer/content tx-view)))))

      (if (seq network-sends)
        (ui/info {::ui/title "Sends"}
          (data-viewer/data-viewer sends-view)))

      (ui/info {::ui/title "Diff added"}
        (if (::data-viewer/content diff-add-view)
          (data-viewer/data-viewer diff-add-view {:raw? true})
          (dom/button {:onClick (fn []
                                  (fp/transact! this [(compute-diff props)]))} "Compute")))

      (ui/info {::ui/title "Diff removed"}
        (if (::data-viewer/content diff-rem-view)
          (data-viewer/data-viewer diff-rem-view {:raw? true})
          (dom/button {:onClick (fn []
                                  (fp/transact! this [(compute-diff props)]))} "Compute")))

      (if component
        (ui/info {::ui/title "Component"}
          (ui/comp-display-name {}
            (str component))))

      (ui/info {::ui/title "State before"}
        (data-viewer/data-viewer old-state-view {:allow-stale? false}))

      (ui/info {::ui/title "State after"}
        (data-viewer/data-viewer new-state-view {:allow-stale? false})))))

(def transaction (fp/factory Transaction {:keyfn ::tx-id}))

(defn compressible? [tx]
  (some-> tx (get tx-options) :compressible?))

(defn compress-transactions? [tx1 tx2]
  (and (compressible? tx1)
    (compressible? tx2)
    (= (some-> tx1 :fulcro.history/tx first)
      (some-> tx2 :fulcro.history/tx first))
    (= (some-> tx1 :ident-ref)
      (some-> tx2 :ident-ref))))

(defmutation add-tx [tx]
  (action [{:keys [state ref] :as env}]
    (let [last-tx-ref (-> @state (get-in ref) ::tx-list last)
          last-tx     (get-in @state last-tx-ref)
          tx          (merge tx {::tx-id (random-uuid)})]
      (if (compress-transactions? tx last-tx)
        (let [compress-count (get last-tx :ui/compress-count 0)
              tx             (assoc tx :ui/compress-count (inc compress-count))]
          (h/swap-entity! env update ::tx-list #(subvec % 0 (dec (count %))))
          (swap! state h/deep-remove-ref last-tx-ref)
          (if (= last-tx-ref (-> @state (get-in ref) ::active-tx))
            (do
              (h/create-entity! env TransactionRow tx :append ::tx-list :replace ::active-tx)
              (swap! state merge/merge-component Transaction (fp/get-initial-state Transaction tx)))
            (h/create-entity! env TransactionRow tx :append ::tx-list)))
        (do
          (h/create-entity! env TransactionRow tx :append ::tx-list)
          (h/swap-entity! env update ::tx-list #(->> (take-last 100 %) vec)))))))

(defmutation select-tx [tx]
  (action [env]
    (let [{:keys [state ref]} env
          tx-ref (fp/ident Transaction tx)
          {:ui/keys [full-computed?]
           :as      transaction} (fdn/db->tree (fp/get-query TransactionRow) (get-in @state tx-ref) @state)]
      (if-not full-computed?
        (swap! state merge/merge-component Transaction
          (fp/get-initial-state Transaction transaction)))
      (swap! state update-in ref assoc ::active-tx tx-ref))))

(defmutation clear-transactions [_]
  (action [env]
    (let [{:keys [state ref]} env
          tx-refs (get-in @state (conj ref ::tx-list))]
      (swap! state update-in ref assoc ::tx-list [] ::active-tx nil)
      (if (seq tx-refs)
        (swap! state #(reduce h/deep-remove-ref % tx-refs))))))

(defmutation replay-tx [_]
  (remote [env]
    (h/remote-mutation env 'transact)))

(fp/defsc TransactionList
  [this
   {::keys [tx-list active-tx tx-filter]}]
  {:initial-state  (fn [_] {::tx-list-id (random-uuid)
                            ::tx-list    []
                            ::active-tx  nil
                            ::tx-filter  ""})
   :pre-merge      (fn [{:keys [current-normalized data-tree]}]
                     (merge {::tx-list-id (random-uuid)
                             ::tx-list    []
                             ::tx-filter  ""}
                       current-normalized data-tree))
   :ident          [::tx-list-id ::tx-list-id]
   :query          [::tx-list-id ::tx-filter
                    {::active-tx (fp/get-query Transaction)}
                    {::tx-list (fp/get-query TransactionRow)}]
   :css            [[:.container {:display        "flex"
                                  :width          "100%"
                                  :flex           "1"
                                  :flex-direction "column"}]

                    [:.transactions {:flex     "1"
                                     :overflow "auto"}]]
   :css-include    [Transaction TransactionRow ui/CSS]
   :initLocalState (fn [this _]
                     {:select-tx (fn [tx]
                                   (fp/transact! this [(select-tx tx)]))
                      :replay-tx (fn [{:keys [tx ident-ref]}]
                                   (fp/transact! this [(replay-tx {:tx tx :tx-ref ident-ref})]))})}

  (let [tx-list (if (seq tx-filter)
                  (filterv #(str/includes? (-> % :fulcro.history/tx pr-str) tx-filter) tx-list)
                  tx-list)]
    (dom/div :.container
      (ui/toolbar {}
        (ui/toolbar-action {:onClick #(fp/transact! this [`(clear-transactions {})])}
          (ui/icon {:title "Clear transactions"} :do_not_disturb))
        (ui/toolbar-separator)
        (ui/toolbar-text-field {:placeholder "Filter"
                                :value       tx-filter
                                :onChange    #(mutations/set-string! this ::tx-filter :event %)}))
      (dom/div :.transactions
        (if (seq tx-list)
          (->> tx-list
            rseq
            (mapv #(transaction-row %
                     {::on-select (fp/get-state this :select-tx)
                      ::on-replay (fp/get-state this :replay-tx)
                      ::selected? (= (::tx-id active-tx) (::tx-id %))})))))
      (if active-tx
        (ui/focus-panel {:style {:height (str (or (fp/get-state this :detail-height) 400) "px")}}
          (ui/drag-resize this {:attribute :detail-height :default 400}
            (ui/toolbar {::ui/classes [:details]}
              (ui/toolbar-spacer)
              (ui/toolbar-action {:onClick #(mutations/set-value! this ::active-tx nil)}
                (ui/icon {:title "Close panel"} :clear))))
          (ui/focus-panel-content {}
            (transaction active-tx)))))))

(def transaction-list (fp/factory TransactionList {:keyfn ::tx-list-id}))
