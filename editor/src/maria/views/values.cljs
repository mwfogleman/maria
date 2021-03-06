(ns maria.views.values
  (:require [goog.object :as gobj]
            [shapes.core :as shapes]
            [cells.cell :as cell]
            [maria.friendly.messages :as messages]
            [maria.views.icons :as icons]
            [re-view.util :as v-util]
            [re-view.core :as v :refer [defview]]
            [maria.editors.code :as code]
            [maria.live.source-lookups :as source-lookups]
            [maria.views.repl-specials :as special-views]
            [maria.views.error :as error-view]
            [re-view.hiccup.core :as hiccup]
            [maria.util :refer [space]]
            [maria.eval :as e]
            [lark.value-viewer.core :as views]
            [lark.tree.core :as tree]
            [lark.tree.range :as range]
            [fast-zip.core :as z])
  (:import [goog.async Deferred]))

(defn highlights-for-position
  "Return ranges for appropriate highlights for a position within given Clojure source."
  [source position]
  (when-let [highlights (some-> (tree/ast (:ns @e/c-env) source)
                                (tree/ast-zip)
                                (tree/node-at position)
                                (z/node)
                                (tree/node-highlights))]
    (case (count highlights)
      0 nil
      1 (first highlights)
      2 (merge (second highlights)
               (range/bounds (first highlights) :left)))))

(defn bracket-type [value]
  (cond (vector? value) ["[" "]"]
        (set? value) ["#{" "}"]
        (map? value) ["{" "}"]
        :else ["(" ")"]))

(defn wrap-value [[lb rb] value]
  [:.inline-flex.items-stretch
   [:.flex.items-start.nowrap lb]
   [:div.v-top value]
   [:.flex.items-end.nowrap rb]])

(extend-protocol hiccup/IEmitHiccup
  shapes/Shape
  (to-hiccup [this] (shapes/to-hiccup this)))

(extend-protocol cell/IRenderHiccup
  object
  (render-hiccup [this] (hiccup/element this)))

(declare format-function)
(extend-protocol views/IView
  cell/Cell
  (view [this] (cell/view this))
  function
  (view [this] (format-function this)))

(declare format-value)

(defview display-deferred
  {:view/will-mount (fn [{:keys [deferred view/state]}]
                      (-> deferred
                          (.addCallback #(swap! state assoc :value %1))
                          (.addErrback #(swap! state assoc :error %))))}
  [{:keys [view/state]}]
  (let [{:keys [value error] :as s} @state]
    [:div
     [:.gray.i "goog.async.Deferred"]
     [:.pv3 (cond (nil? s) [:.progress-indeterminate]
                  error (str error)
                  :else (or (some-> value (format-value)) [:.gray "Finished."]))]]))

(def expander-outter :.dib.bg-darken.ph2.pv1.mh1.br2)
(def inline-centered :.inline-flex.items-center)

(def ^:dynamic *format-depth-limit* 3)

(defn expanded? [{:keys [view/state]} depth]
  (if (boolean? (:collection-expanded? @state))
    (:collection-expanded? @state)
    (and depth (< depth *format-depth-limit*))))

(defn toggle-depth [{:keys [view/state] :as this} depth label]
  (let [is-expanded? (expanded? this depth)
        class (if is-expanded?
                "cursor-zoom-out hover-bg-darken "
                "cursor-zoom-in gray hover-black")]
    [:.dib {:class    class
            :on-click #(swap! state assoc :collection-expanded? (not is-expanded?))} label]))

(defview format-collection
  {:view/initial-state {:limit-n              20
                        :collection-expanded? nil}}
  [{state :view/state :as this} depth value]
  (let [{:keys [limit-n]} @state
        [lb rb] (bracket-type value)
        more? (= (count (take (inc limit-n) value)) (inc limit-n))
        hover-class (if (even? depth) "hover-bg-darken" "hover-bg-lighten")]
    (cond (empty? value)
          (str space lb rb space)
          (expanded? this depth) [:.inline-flex.items-stretch
                                  {:class hover-class}
                                  [:.flex.items-start.nowrap (if (empty? value) (str space lb)
                                                                                (toggle-depth this depth (str space lb space)))]
                                  [:div.v-top (interpose " " (v-util/map-with-keys (partial format-value (inc depth)) (take limit-n value)))]
                                  (when more? [:.flex.items-end [expander-outter {:class    "pointer"
                                                                                  :on-click #(swap! state update :limit-n + 20)} "…"]])
                                  [:.flex.items-end.nowrap (str space rb space)]]
          :else [:.inline-flex.items-center.gray.nowrap
                 {:class hover-class} (toggle-depth this depth (str space lb "…" rb space))])))

(defview format-map
  {:view/initial-state {:limit-n              20
                        :collection-expanded? nil}}
  [{state :view/state :as this} depth value]
  (let [{:keys [limit-n]} @state
        [lb rb] (bracket-type value)
        more? (= (count (take (inc limit-n) value)) (inc limit-n))
        last-n (if more? limit-n (count value))
        hover-class (if (even? depth) "hover-bg-darken" "hover-bg-lighten")]
    (if (or (empty? value) (expanded? this depth))
      [:table.relative.inline-flex.v-mid
       {:class hover-class}
       [:tbody
        (or (some->> (seq (take limit-n value))
                     (map-indexed (fn [n [a b]]
                                    [:tr
                                     {:key n}
                                     [:td.v-top.nowrap
                                      (when (= n 0) (toggle-depth this depth (str space lb space)))]
                                     [:td.v-top
                                      (format-value (inc depth) a) space]
                                     [:td.v-top
                                      (format-value (inc depth) b)]
                                     [:td.v-top.nowrap (when (= (inc n) last-n) (str space rb space))]])))
            [:tr [:td.hover-bg-darken.nowrap (str space lb rb space)]])
        (when more? [:tr [:td {:col-span 2}
                          [expander-outter {:on-click #(swap! state update :limit-n + 20)} [inline-centered "…"]]]])]]
      [:.inline-flex.items-center.gray
       {:class hover-class} (toggle-depth this depth (str space lb "…" rb space))])))

(defview format-function
  {:view/initial-state (fn [_ value] {:expanded? false})}
  [{:keys [view/state]} value]
  (let [{:keys [expanded?]} @state
        fn-name (some-> (source-lookups/fn-name value) (symbol) (name))]

    [:span
     [expander-outter {:on-click #(swap! state update :expanded? not)}
      [inline-centered
       (if (and fn-name (not= "" fn-name))
         (some-> (source-lookups/fn-name value) (symbol) (name))
         [:span.o-50.mr1 "ƒ"])
       (-> (if expanded? icons/ArrowPointingUp
                         icons/ArrowPointingDown)
           (icons/size 20)
           (icons/class "mln1 mrn1 o-50"))]
      (when expanded?
        (or (some-> (source-lookups/js-source->clj-source (.toString value))
                    (code/viewer))
            (some-> (source-lookups/fn-var value)
                    (special-views/var-source))))]]))

(def format-value views/format-value)

(defn display-source [{:keys [source error error/position warnings]}]
  [:.code.overflow-auto.pre.gray.mv3.ph3
   {:style {:max-height 200}}
   (code/viewer {:error-ranges (cond-> []
                                       position (conj (highlights-for-position source position))
                                       (seq warnings) (into (map #(highlights-for-position source (:warning-position %)) warnings)))}
                source)])

(defn format-warnings [warnings]
  (sequence (comp (map messages/reformat-warning)
                  (distinct)
                  (keep identity)) warnings))

(defn render-error-result [{:keys [error source show-source? formatted-warnings warnings] :as result}]
  [:div
   {:class "bg-darken-red cf"}
   (when source
     (display-source result))
   [:.ws-prewrap.relative      ;.mv3.pv1
    [:.ph3.overflow-auto
     (->> (for [message (concat (or formatted-warnings
                                    (format-warnings warnings))
                                (messages/reformat-error result))
                :when message]
            [:.mv2 message])
          (interpose [:.bb.b--red.o-20.bw2]))]]])

(defview display-result
  {:key :id}
  [{:keys [value
           error
           warnings
           show-source?
           block-id
           source]
    result :view/props
    :as this}]
  (error-view/error-boundary {:on-error (fn [{:keys [error]}]
                                          (e/handle-block-error block-id error))
                              :error-content (fn [{:keys [error info]}]
                                               (-> result
                                                   (assoc :error (or error (js/Error. "Unknown error"))
                                                          :error/kind :eval)
                                                   (e/add-error-position)
                                                   (render-error-result)))}
                             (let [warnings (format-warnings warnings)
                                   error? (or error (seq warnings))]
                               (when error
                                 (.error js/console error))
                               (if error?
                                 (render-error-result (assoc result :formatted-warnings warnings))
                                 [:div
                                  (when (and source show-source?)
                                    (display-source result))
                                  [:.ws-prewrap.relative
                                   [:.ph3 (format-value value)]]]))))

(defn repl-card [& content]
  (into [:.sans-serif.bg-white.shadow-4.ma2] content))
