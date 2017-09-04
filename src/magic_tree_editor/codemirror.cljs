(ns magic-tree-editor.codemirror
  (:require [cljsjs.codemirror :as CM]
            [fast-zip.core :as z]
            [goog.events :as events]
            [magic-tree.core :as tree]
            [magic-tree-editor.util :as cm]
            [goog.events.KeyCodes :as KeyCodes]
            [maria-commands.registry :as registry]
            [cljs.core.match :refer-macros [match]]
            [re-db.d :as d]
            [goog.dom :as gdom]
            [goog.object :as gobj]
            [maria.eval :as e]
            [magic-tree-editor.util :as cm-util]))

(def Pos CM/Pos)
(def changeEnd CM/changeEnd)

(defn cm-pos
  "Return a CodeMirror position."
  [pos]
  (if (map? pos)
    (CM/Pos (:line pos) (:column pos))
    pos))

(defn cursor->range [cursor]
  {:line       (.-line cursor)
   :column     (.-ch cursor)
   :end-line   (.-line cursor)
   :end-column (.-ch cursor)})

(defn cursor-bookmark []
  (gdom/createDom "div" #js {"className" "cursor-marker"}))

(extend-type js/CodeMirror
  ILookup
  (-lookup
    ([this k] (get (aget this "cljs$state") k))
    ([this k not-found] (get (aget this "cljs$state") k not-found)))
  ISwap
  (-swap!
    ([this f] (aset this "cljs$state" (f (aget this "cljs$state"))))
    ([this f a] (aset this "cljs$state" (f (aget this "cljs$state") a)))
    ([this f a b] (aset this "cljs$state" (f (aget this "cljs$state") a b)))
    ([this f a b xs]
     (aset this "cljs$state" (apply f (concat (list (aget this "cljs$state") a b) xs))))))

(extend-type js/CodeMirror.Pos
  IComparable
  (-compare [x y]
    (CM/cmpPos x y))
  IEquiv
  (-equiv [x y]
    (and y
         (= (.-line x) (.-line y))
         (= (.-ch x) (.-ch y))))
  IPrintWithWriter
  (-pr-writer [pos writer _]
    (-write writer (str "#Pos[" (.-line pos) ", " (.-ch pos) "]")))
  ILookup
  (-lookup
    ([o k] (gobj/get o k))
    ([o k not-found] (gobj/get o k not-found))))

(.defineOption js/CodeMirror "cljsState" false
               (fn [cm] (aset cm "cljs$state" (or (aget cm "cljs$state") {}))))

(def M1 (registry/modifier-keycode "M1"))
(def SHIFT (registry/modifier-keycode "SHIFT"))

(defn modifier-down? [k]
  (contains? (d/get :commands :modifiers-down) k))

(defn cursor-loc
  "Current sexp, or nearest sexp to the left, or parent."
  [pos loc]
  (let [cursor-loc (if-not (tree/whitespace? (z/node loc))
                     loc
                     (if (and (= pos (select-keys (z/node loc) [:line :column]))
                              (z/left loc)
                              (not (tree/whitespace? (z/node (z/left loc)))))
                       (z/left loc)
                       loc))
        up-tag (some-> cursor-loc
                       (z/up)
                       (z/node)
                       (:tag))]
    (cond-> cursor-loc
            (#{:quote :deref
               :reader-conditional} up-tag)
            (z/up))))


(defn set-cursor-root! [cm]
  (swap! cm assoc :cursor-root/marker (.setBookmark cm
                                                    (.getCursor cm)
                                                    #js {:widget (cursor-bookmark)})))

(defn unset-cursor-root! [cm]
  (when-let [marker (:cursor-root/marker cm)]
    (.clear marker)
    (swap! cm dissoc :cursor-root/marker)))

(defn cursor-root [cm]
  (when-let [marker (:cursor-root/marker cm)]
    (.find marker)))

(defn return-cursor-to-root! [cm]
  (when (.somethingSelected cm)
    (some->> (cursor-root cm)
             (.setCursor cm)))
  (unset-cursor-root! cm))

(defn get-cursor [cm]
  (or (cursor-root cm)
      (.getCursor cm)))

(defn selection? [cm]
  (.somethingSelected cm))

(defn selection-text
  "Return selected text, or nil"
  [cm]
  (when (.somethingSelected cm)
    (.getSelection cm)))

(defn set-cursor! [cm pos]
  (unset-cursor-root! cm)
  (let [pos (cm-pos pos)]
    (.setCursor cm pos pos)))

(defn set-preserve-cursor!
  "If value is different from editor's current value, set value, retain cursor position"
  [editor value]
  (when-not (identical? value (.getValue editor))
    (let [cursor-pos (get-cursor editor)]
      (.setValue editor (str value))
      (if (-> editor (aget "state" "focused"))
        (.setCursor editor cursor-pos)))))

(defn range->positions
  "Given a Clojure-style column and line range, return Codemirror-compatible `from` and `to` positions"
  [{:keys [line column end-line end-column]}]
  [(CM/Pos line column)
   (CM/Pos end-line end-column)])

(defn mark-ranges!
  "Add marks to a collection of Clojure-style ranges"
  [cm ranges payload]
  (doall (for [[from to] (map range->positions ranges)]
           (.markText cm from to payload))))

(defn range-text [cm range]
  (let [[from to] (range->positions range)]
    (.getRange cm from to)))

(defn select-range
  "Copy a {:line .. :column ..} range from a CodeMirror instance."
  [cm range]
  (let [[from to] (range->positions range)]
    (.setSelection cm from to #js {:scroll false})))

(defn replace-range!
  ([cm s from {:keys [line column]}]
   (replace-range! cm s (merge from {:end-line line :end-column column})))
  ([cm s {:keys [line column end-line end-column]}]
   (.replaceRange cm s
                  (Pos line column)
                  (Pos (or end-line line) (or end-column column)))))

(defn select-node! [cm node]
  (when (and (not (tree/whitespace? node))
             (or (not (.somethingSelected cm))
                 (cursor-root cm)))
    (when-not (cursor-root cm)
      (set-cursor-root! cm))
    (select-range cm (tree/bounds node))))

(defn pos->boundary
  ([pos]
   {:line   (or (.-line pos) 0)
    :column (.-ch pos)})
  ([pos side]
   (case side :left {:line   (or (.-line pos) 0)
                     :column (.-ch pos)}
              :right {:end-line   (or (.-line pos) 0)
                      :end-column (.-ch pos)})))

(defn selection-bounds
  [cm]
  (if (.somethingSelected cm)
    (let [sel (first (.listSelections cm))]
      (pos->boundary (.from sel))
      (merge (pos->boundary (.from sel) :left)
             (pos->boundary (.to sel) :right)))
    (let [cur (get-cursor cm)]
      (pos->boundary cur))))

(defn highlight-range [pos node]
  (if (and (tree/has-edges? node)
           (tree/within? (tree/inner-range node) pos))
    (tree/inner-range node)
    node))

(defn update-selection! [cm e]
  (let [key-code (KeyCodes/normalizeKeyCode (.-keyCode e))
        evt-type (.-type e)
        m-down? (modifier-down? M1)
        shift-down? (modifier-down? SHIFT)]
    (match [m-down? evt-type key-code]
           [true _ (:or 16 91)] (let [pos (cursor->range (get-cursor cm))
                                      loc (cond-> (get-in cm [:magic/cursor :bracket-loc])
                                                  shift-down? (tree/top-loc))]
                                  (some->> loc
                                           (z/node)
                                           (highlight-range pos)
                                           (select-node! cm)))
           [_ "keyup" 91] (return-cursor-to-root! cm)
           :else (when-not (contains? #{16 M1} key-code)
                   (unset-cursor-root! cm)))))

(defn clear-brackets! [cm]
  (doseq [handle (get-in cm [:magic/cursor :handles])]
    (.clear handle))
  (swap! cm update :magic/cursor dissoc :handles))

(defn match-brackets! [cm node]
  (let [prev-node (get-in cm [:magic/cursor :node])]
    (when (not= prev-node node)
      (clear-brackets! cm)
      (when (some-> node (tree/may-contain-children?))
        (swap! cm assoc-in [:magic/cursor :handles]
               (mark-ranges! cm (tree/node-highlights node) #js {:className "CodeMirror-matchingbracket"}))))))

(defn clear-parse-errors! [cm]
  (doseq [handle (get-in cm [:magic/errors :handles])]
    (.clear handle))
  (swap! cm update :magic/errors dissoc :handles))

(defn highlight-parse-errors! [cm errors]
  (let [error-ranges (map (comp :position second) errors)
        ;; TODO
        ;; derive className from error name, not all errors are unmatched brackets.
        ;; (somehow) add a tooltip or other attribute to the marker (for explanation).
        handles (mark-ranges! cm error-ranges #js {:className "CodeMirror-unmatchedBracket"})]
    (swap! cm assoc-in [:magic/errors :handles] handles)))

(defn update-ast!
  [{:keys [ast] :as cm}]
  (when-let [{:keys [errors] :as next-ast} (try (tree/ast (:ns @e/c-env) (.getValue cm))
                                                (catch js/Error e (.debug js/console e)))]
    (when (not= next-ast ast)
      (when-let [on-ast (-> cm :view :on-ast)]
        (on-ast next-ast))
      (let [next-zip (tree/ast-zip next-ast)]
        (clear-parse-errors! cm)
        (when-let [error (first errors)]
          (highlight-parse-errors! cm [error]))
        (if (seq errors)
          (swap! cm dissoc :ast :zipper)
          (swap! cm assoc
                 :ast next-ast
                 :zipper next-zip))))))

(defn update-cursor!
  [{:keys [zipper magic/brackets?] :as cm}]
  (when-let [position (pos->boundary (get-cursor cm))]
    (when-let [loc (some-> zipper (tree/node-at position))]
      (let [bracket-loc (cursor-loc position loc)
            bracket-node (z/node bracket-loc)]
        (when brackets? (match-brackets! cm bracket-node))
        (swap! cm update :magic/cursor merge {:loc          loc
                                              :node         (z/node loc)
                                              :bracket-loc  bracket-loc
                                              :bracket-node bracket-node
                                              :pos          position})))))

(defn require-opts [cm opts]
  (doseq [opt opts] (.setOption cm opt true)))

(.defineOption js/CodeMirror "magicTree" false
               (fn [cm on?]
                 (when on?
                   (require-opts cm ["cljsState"])
                   (.on cm "change" update-ast!)
                   (update-ast! cm))))

(.defineOption js/CodeMirror "magicCursor" false
               (fn [cm on?]
                 (when on?
                   (require-opts cm ["magicTree"])
                   (.on cm "cursorActivity" update-cursor!)
                   (update-cursor! cm))))

(.defineOption js/CodeMirror "magicBrackets" false
               (fn [cm on?]
                 (when on?
                   (require-opts cm ["magicCursor"])

                   (.on cm "keyup" update-selection!)
                   (.on cm "keydown" update-selection!)
                   (events/listen js/window "blur" #(return-cursor-to-root! cm))
                   (events/listen js/window "blur" #(clear-brackets! cm))

                   (swap! cm assoc :magic/brackets? true))))