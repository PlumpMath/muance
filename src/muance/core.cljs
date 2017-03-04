(ns muance.core
  (:require [goog.object :as o]))

(def ^{:private true} index-typeid 0)
(def ^{:private true} index-parent-vnode 1)
(def ^{:private true} index-node 2)
(def ^{:private true} index-component 3)
(def ^{:private true} index-children-count 4)
(def ^{:private true} index-children 5)
(def ^{:private true} index-attrs-count 6)
(def ^{:private true} index-attrs 7)
(def ^{:private true} index-unmount 8)
(def ^{:private true} index-key 9)
(def ^{:private true} index-key-moved 10)
(def ^{:private true} index-keymap 11)
(def ^{:private true} index-keymap-invalid 12)

(def ^{:private true} index-text 3)

;; component specific data
(def ^{:private true} index-comp-data 2)
(def ^{:private true} index-comp-props 3)
(def ^{:private true} index-comp-state 6)
(def ^{:private true} index-comp-state-ref 7)

(def ^{:private true} index-comp-data-name 0)
(def ^{:private true} index-comp-data-svg-namespace 1)
(def ^{:private true} index-comp-data-index-in-parent 2)
(def ^{:private true} index-comp-data-depth 3)
(def ^{:private true} index-comp-data-dirty-flag 4)

(def ^{:private true} vnode-stateful-key "muance.core/vnode-stateful")

(def ^{:dynamic true :private true} *component* nil)
;; Whether the current vnode has just been created or not
(def ^{:dynamic true :private true} *new-node* nil)
;; Set to the value of the moved node when a moved node is met, unless if was already set before
;; Thus it keeps the value of the higher moved node in the tree, even if child nodes are
;; themselves moved. This is necessary to know when to unset the value 
(def ^{:dynamic true :private true} *moved-vnode* nil)
(def ^{:dynamic true :private true} *props* nil)
(def ^{:dynamic true :private true} *skip* nil)
(def ^{:dynamic true :private true} *component-depth* nil)
;; Used to avoid re-rendering when a state update is done from a will-receive-props hook
(def ^{:dynamic true :private true} *watch-local-state* true)
(def ^{:dynamic true :private true} *components-queue-count* nil)
(def ^{:dynamic true :private true} *render-queue* nil)
;; incremented on svg open, decremented on svg close, reseted to 0 on foreignObject open,
;; previous value restored on foreignObject close
(def ^{:dynamic true :private true} *svg-namespace* nil)

(defonce ^{:private true} moved-flag nil)
(defonce ^{:private true} no-props-flag #js [])
;; Used to enqueue components with a did-mount / will-unmount hook, and then call the hooks
;; in order
(def ^{:private true} *components-queue* #js [])

(def svg-ns "http://www.w3.org/2000/svg")
(def xml-ns "http://www.w3.org/XML/1998/namespace")
(def xlink-ns "http://www.w3.org/1999/xlink")

(def ^:dynamic *state* nil)
(def ^:dynamic *vnode* nil)

(declare process-render-queue)

(defn- component? [vnode]
  (< (aget vnode index-typeid) 0))

(defn- dirty-component? [vnode]
  (aget vnode index-comp-data index-comp-data-dirty-flag))

(defn component-name [vnode]
  (assert vnode "muance.core/component-name expects a vnode.")
  (if (component? vnode)
    (aget vnode index-comp-data index-comp-data-name)
    (aget vnode index-component index-comp-data index-comp-data-name)))

(defn moving? [vnode]
  (assert vnode "muance.core/moving? expects a vnode.")
  (boolean *moved-vnode*))

(defn- dom-nodes* [acc vnode]
  (if (component? vnode)
    (when-let [children (aget vnode index-children)]
      (let [l (.-length children)]
        (loop [i 0]
          (when (< i l)
            (dom-nodes* acc (aget children i))
            (recur (inc i))))))
    (.push acc (aget vnode index-node)))
  acc)

(declare ref-node-down)

(defn dom-nodes [vnode]
  (assert vnode "muance.core/dom-nodes expects a vnode.")
  (if (component? vnode)
    (dom-nodes* #js [] vnode)
    #js [(aget vnode index-node)]))

(defn dom-node [vnode]
  (assert vnode "muance.core/dom-node expects a vnode.")
  (if (component? vnode)
    (ref-node-down vnode)
    (aget vnode index-node)))

(defn- remove-vnode-key [vnode key]
  (aset vnode index-parent-vnode nil)
  (aset *vnode* index-keymap-invalid
        (inc (aget *vnode* index-keymap-invalid))))

(defn- on-state-change [k r o n]
  (when *watch-local-state*
    (let [stateful-data (o/get r vnode-stateful-key)
          vnode (aget stateful-data 0)
          comp-fn (aget stateful-data 1)
          render-queue (aget stateful-data 2)
          component-depth (aget vnode index-comp-data index-comp-data-depth)]
      (when (not (dirty-component? vnode))
        (aset (aget vnode index-comp-data) index-comp-data-dirty-flag true)
        (if-let [dirty-comps (aget render-queue component-depth)]
          (do (.push dirty-comps comp-fn)
              (.push dirty-comps vnode))
          (aset render-queue component-depth #js [comp-fn vnode]))
        (when-not (aget render-queue 0)
          (aset render-queue 0 true)
          (.requestAnimationFrame js/window
                                  (fn []
                                    (process-render-queue render-queue))))))))

(defn- remove-real-node [vnode]
  (if (component? vnode)
    (when-let [children (aget vnode index-children)]
      (let [l (.-length children)]
        (loop [i 0]
          (when (< i l)
            (remove-real-node (aget children i))
            (recur (inc i))))))
    (let [node (aget vnode index-node)]
      (when-let [p (.-parentNode node)]
        (.removeChild p node)))))

(defn- enqueue-unmounts [vnode]
  (when (component? vnode)
    (remove-watch (aget vnode index-comp-state-ref) ::component))
  (when (aget vnode index-unmount)
      (aset *components-queue* *components-queue-count* vnode)
      (set! *components-queue-count* (inc *components-queue-count*)))
  (when-let [children (aget vnode index-children)]
    (let [children-count (.-length children)]
      (loop [i 0]
        (when (< i children-count)
          (let [child (aget vnode index-children i)]            
            (enqueue-unmounts child))
          (recur (inc i)))))))

(defn- call-unmounts [queue-start]
  (loop [i (dec *components-queue-count*)]
    (when (>= i queue-start)
      (let [vnode (aget *components-queue* i)
            component (if (component? vnode) vnode (aget vnode index-component))
            props (aget component index-comp-props)
            state (aget component index-comp-state)]
        ;; *vnode* is rebound in remove-node
        (set! *vnode* vnode)
        ((aget vnode index-unmount) props state))
      (recur (dec i))))
  (set! *components-queue-count* queue-start))

(defn- remove-node [vnode]
  (let [current-vnode *vnode*
        queue-start *components-queue-count*]
    (enqueue-unmounts vnode)
    (call-unmounts queue-start)
    (set! *vnode* current-vnode))
  (remove-real-node vnode))

(defn- clean-keymap [vnode]
  (when-let [keymap (aget vnode index-keymap)]
    (when (> (aget vnode index-keymap-invalid) 0)
      (o/forEach keymap
                 (fn [v k]
                   (when (nil? (aget v index-parent-vnode))
                     (o/remove keymap k)
                     (remove-node v))))
      (aset vnode index-keymap-invalid 0))))

(defn- clean-children [vnode]
  (when-let [children (aget vnode index-children)]
    (let [children-count (aget vnode index-children-count)
          children-length (.-length children)]
      (loop [l children-length]
        (when (> l children-count)
          (let [removed-vnode (.pop children)
                k (aget removed-vnode index-key)]
            (if k
              (remove-vnode-key removed-vnode key)
              (remove-node removed-vnode)))
          (recur (dec l))))
      (aset vnode index-children-count 0))))

(defn- set-attribute [node ns key val]
  (if (nil? val)
    (.removeAttribute node key)
    (if (nil? ns)
      (.setAttribute node key val)
      (.setAttributeNS node ns key val))))

(defn- set-property [node ns key val]
  (o/set node key val))

(defn- set-input-value [node ns key val]
  (when (not= (o/get node key) val)
    (o/set node key val)))

(defn- set-style [node ns key val]
  (o/set (.-style node) key val))

(defn- set-style-custom [node ns key val]
  (.setProperty (.-style node) key val))

(defn- init-keymap [keymap]
  (aset *vnode* index-keymap keymap)
  (aset *vnode* index-keymap-invalid 0)
  keymap)

(defn- new-vnode [typeid element]
  #js [typeid *vnode* element])

(defn- new-vnode-key [typeid element keymap key]
  (let [keymap (if (nil? keymap) (init-keymap #js {}) keymap)
        vnode #js [typeid *vnode* element
                   nil nil nil nil nil nil key moved-flag]]
    (o/set keymap key vnode)
    vnode))

(defn- new-text-vnode [element text]
  #js [0 *vnode* element text])

(defn- create-element [tag]
  ;; tag is nil when opening a component
  (when tag
    (if (> *svg-namespace* 0)
      (.createElementNS js/document svg-ns tag)
      (.createElement js/document tag))))

(defn- parent-node [parent]
  (if (component? parent)
    (recur (aget parent index-parent-vnode))
    (aget parent index-node)))

(defn- ref-node-down [vnode]
  (if (component? vnode)
    (when-let [children (aget vnode index-children)]
      (let [l (.-length children)]
        (loop [i 0]
          (when (< i l)
            (if-let [node (ref-node-down (aget children i))]
              node
              (recur (inc i)))))))
    (aget vnode index-node)))

(defn- ref-node-up [vnode]
  ;; index-children has already been incremented
  ;; children cannot be nil
  (let [children (aget vnode index-children)
        l (.-length children)
        index (aget vnode index-children-count)
        found-node (loop [i index
                          found-node nil]
                     (if found-node
                       found-node
                       (when (< i l)
                         (recur (inc i) (ref-node-down (aget children i))))))]
    (if (nil? found-node)
      (when (component? vnode)
        (recur (aget vnode index-parent-vnode)))
      found-node)))

(defn- insert-before* [parent-node vnode ref-node]
  (if (component? vnode)
    (when-let [children (aget vnode index-children)]
      (let [l (.-length children)]
        (loop [i 0]
          (when (< i l)
            (insert-before* parent-node (aget children i) ref-node)
            (recur (inc i))))))
    (.insertBefore parent-node (aget vnode index-node) ref-node)))

(defn- insert-before [parent-vnode vnode ref-vnode]
  (if (component? parent-vnode)
    (let [parent-node (parent-node parent-vnode)]
      (if-let [ref-node (when ref-vnode (ref-node-down ref-vnode))]
        (insert-before* parent-node vnode ref-node)
        (insert-before* parent-node vnode (ref-node-up parent-vnode))))
    (let [parent-node (aget parent-vnode index-node)]
      (if (nil? ref-vnode)
        (insert-before* parent-node vnode nil)
        (if-let [ref-node (ref-node-down ref-vnode)]
          (insert-before* parent-node vnode ref-node)
          (insert-before* parent-node vnode (ref-node-up parent-vnode)))))))

(defn- splice-to [nodes index moved-node to-node]
  (let [next-moved-node (aget nodes index)]
    (aset nodes index moved-node)
    (when (and next-moved-node (not (identical? next-moved-node to-node)))
      (recur nodes (inc index) next-moved-node to-node))))

(defn- call-will-receive-props [prev-props props state-ref will-receive-props]
  (when (and will-receive-props (not (identical? prev-props props)))
    (will-receive-props prev-props props state-ref)
    (set! *state* @state-ref)))

(defn- comp-props [vnode]
  (let [props (aget vnode index-comp-props)]
    (if (identical? props no-props-flag)
      nil props)))

;; (= tag nil) means this is the opening of a component
(defn- open-impl [tag typeid key vnode-index will-update will-receive-props]
  (let [key (when key (str key))
        parent-children (or (aget *vnode* index-children) #js [])
        prev (aget parent-children vnode-index)
        prev-key (when prev (aget prev index-key))
        prev-typeid (when prev (aget prev index-typeid))
        keymap (aget *vnode* index-keymap)]
    (aset *vnode* index-children-count (inc vnode-index))
    (when (nil? (aget *vnode* index-children))
      (aset *vnode* index-children parent-children))
    (if (and (= key prev-key) (= typeid prev-typeid))
      (let [prev-state (when (nil? tag) (aget prev index-comp-state))
            prev-props (when (nil? tag) (comp-props prev))]
        (when (nil? tag)
          (set! *state* @(aget prev index-comp-state-ref)))
        (when key
          (aset prev index-key-moved moved-flag))
        (set! *vnode* prev)
        (if (and (nil? tag)
                 (identical? prev-props *props*)
                 (identical? prev-state *state*)
                 (nil? *moved-vnode*))
          (set! *skip* true)
          (do
            (when (nil? tag)
              (call-will-receive-props prev-props *props*
                                  (aget prev index-comp-state-ref)
                                  will-receive-props))
            (when will-update (will-update *props* *state*)))))
      (let [moved-vnode (and key keymap (o/get keymap key))]
        (if (and moved-vnode
                 (= typeid (aget moved-vnode index-typeid))
                 (not (identical? moved-flag (aget moved-vnode index-key-moved))))
          (let [prev-props (when (nil? tag) (comp-props moved-vnode))]
            (when (nil? *moved-vnode*)
              (set! *moved-vnode* moved-vnode))
            (aset moved-vnode index-key-moved moved-flag)
            (insert-before *vnode* moved-vnode prev)
            (aset parent-children vnode-index moved-vnode)
            (if (aget moved-vnode index-parent-vnode)
              ;; moved-vnode is amongs the next children -> splice between the
              ;; current index and the index of the moved node
              (splice-to parent-children (inc vnode-index) prev moved-vnode)
              (do
                ;; the moved-node is coming from the previous children -> replace the node
                ;; at the current index
                (aset moved-vnode index-parent-vnode *vnode*)
                (aset *vnode* index-keymap-invalid
                      (dec (aget *vnode* index-keymap-invalid)))
                (if prev-key
                  (remove-vnode-key prev prev-key)
                  (when prev (remove-node prev)))))
            (set! *vnode* moved-vnode)
            (when (nil? tag)
              (let [state-ref (aget moved-vnode index-comp-state-ref)]
                (set! *state* @state-ref)
                (call-will-receive-props prev-props *props* state-ref will-receive-props)))
            (when will-update (will-update *props* *state*)))
          ;; this is a new node -> replace the node at the current index
          (let [vnode (if key
                        (new-vnode-key typeid (create-element tag) keymap key)
                        (new-vnode typeid (create-element tag)))
                ;; handle invalid states
                ;; the prev-key is refreshed since it can be modified by the invalid state
                ;; handling
                prev-key (cond (and moved-vnode
                                    (identical?
                                     moved-flag (aget moved-vnode index-key-moved)))
                               (do
                                 (.error js/console
                                         (str "Duplicate key: " key
                                              " in component "
                                              (component-name moved-vnode)))
                                 (aset moved-vnode index-key nil)
                                 (when prev (aget prev index-key)))
                               (and moved-vnode (not= typeid (aget moved-vnode index-typeid)))
                               (do
                                 #_(.warn
                                  js/console
                                  (str "Nodes with same key and different typeids. key: " key))
                                 (aset moved-vnode index-key nil)
                                 (when (nil? (aget moved-vnode index-parent-vnode))
                                   (aset *vnode* index-keymap-invalid
                                         (dec (aget *vnode* index-keymap-invalid)))
                                   (remove-node moved-vnode))
                                 (when prev (aget prev index-key)))
                               :else prev-key)]
            (insert-before *vnode* vnode prev)
            (aset parent-children vnode-index vnode)
            (set! *new-node* (inc *new-node*))
            (if prev-key
              (remove-vnode-key prev prev-key)
              (when prev (remove-node prev)))
            (when (= tag "foreignObject")
              (set! *svg-namespace* 0))
            (set! *vnode* vnode)))))))

(defn- open [tag typeid key will-update will-unmount]
  (assert (not (nil? *component*))
          (str "tag " tag " was called outside a render loop"))
  (open-impl tag (or typeid tag) key
             (or (aget *vnode* index-children-count) 0)
             will-update nil)
  (aset *vnode* index-component *component*)
  (when (not= (aget *vnode* index-unmount) will-unmount)
    (aset *vnode* index-unmount will-unmount)))

(defn- close-impl [did-mount did-update]
  (clean-children *vnode*)
  (clean-keymap *vnode*)
  (if (> *new-node* 0)
    (do
      (set! *new-node* (dec *new-node*))
      (when did-mount
        (aset *components-queue* *components-queue-count* did-mount)
        (aset *components-queue* (inc *components-queue-count*) *vnode*)
        (set! *components-queue-count* (+ *components-queue-count* 2))))
    (when did-update (did-update *props* *state*)))
  (when (identical? *moved-vnode* *vnode*)
    (set! *moved-vnode* nil)))

(defn- close [did-mount did-update]
  (when (aget *vnode* index-attrs-count)
    (aset *vnode* index-attrs-count 0))
  (close-impl did-mount did-update)
  (set! *vnode* (aget *vnode* index-parent-vnode)))

(defn- text-node [t]
  (let [vnode-index (or (aget *vnode* index-children-count) 0)
        parent-children (or (aget *vnode* index-children) #js [])
        prev (aget parent-children vnode-index)
        prev-key (when prev (aget prev index-key))
        prev-typeid (when prev (aget prev index-typeid))]
    (aset *vnode* index-children-count (inc vnode-index))
    (when (nil? (aget *vnode* index-children))
      (aset *vnode* index-children parent-children))
    (if (= 0 prev-typeid)
      (when (not= (aget prev index-text) t)
        (aset prev index-text t)
        (o/set (aget prev index-node) "nodeValue" t))
      (let [vnode (new-text-vnode (.createTextNode js/document t) t)]
        (insert-before *vnode* vnode (aget parent-children vnode-index))
        (aset parent-children vnode-index vnode)
        (if prev-key
          (remove-vnode-key prev prev-key)
          (when prev (remove-node prev)))))))

(def ^{:private true} hooks-key "muance.core/hooks")

(def ^{:private true} index-hooks-get-initial-state 0)
(def ^{:private true} index-hooks-will-receive-props 1)
(def ^{:private true} index-hooks-did-mount 2)
(def ^{:private true} index-hooks-did-update 3)
(def ^{:private true} index-hooks-will-unmount 4)
(def ^{:private true} index-hooks-will-update 5)

(defn- open-comp [component-name typeid props? props comp-fn key hooks]
  (assert (not (nil? *vnode*))
          (str "tried to render " component-name " outside a render loop"))
  (let [vnode-index (or (aget *vnode* index-children-count) 0)]
    (set! *props* props)
    (if hooks
      (let [will-unmount (aget hooks index-hooks-will-unmount)]
        (open-impl nil typeid key vnode-index
                   (aget hooks index-hooks-will-update)
                   (aget hooks index-hooks-will-receive-props))
        (when (not= (aget *vnode* index-unmount) will-unmount)
          (aset *vnode* index-unmount will-unmount)))
      (do
        (open-impl nil typeid key vnode-index nil nil)
        (when (not= (aget *vnode* index-unmount) nil)
          (aset *vnode* index-unmount nil))))
    (set! *component* *vnode*)
    (when (> *new-node* 0)
      (let [get-initial-state (and hooks (aget hooks index-hooks-get-initial-state))
            state-ref (if get-initial-state
                        (atom (get-initial-state *props*))
                        (atom nil))]
        (o/set state-ref vnode-stateful-key
               #js [*vnode* comp-fn *render-queue*])
        (add-watch state-ref ::component on-state-change)
        (aset *vnode* index-comp-state-ref state-ref))
      (aset *vnode* index-comp-data #js[])
      (set! *state* @(aget *vnode* index-comp-state-ref)))
    (let [comp-data (aget *vnode* index-comp-data)]
      (aset comp-data index-comp-data-name component-name)
      (aset comp-data index-comp-data-svg-namespace *svg-namespace*)
      (aset comp-data index-comp-data-index-in-parent vnode-index)
      (aset comp-data index-comp-data-depth *component-depth*)
      (set! *component-depth* (inc *component-depth*))
      (aset comp-data index-comp-data-dirty-flag nil))
    (aset *vnode* index-comp-props (if props? *props* no-props-flag))
    (aset *vnode* index-comp-state *state*)))

(defn- close-comp [parent-component hooks]
  (when-not *skip*
    (if hooks
      (close-impl (aget hooks index-hooks-did-mount) (aget hooks index-hooks-did-update))
      (close-impl nil nil)))
  (set! *component* parent-component)
  (set! *component-depth* (dec *component-depth*))
  (set! *vnode* (aget *vnode* index-parent-vnode))
  (when parent-component
    (set! *props* (aget parent-component index-comp-props))
    (set! *state* (aget parent-component index-comp-state)))
  (set! *skip* false))

(defn- attr-impl [ns key val set-fn]
  (let [attrs-index (or (aget *vnode* index-attrs-count) 0)
        prev-attrs (or (aget *vnode* index-attrs) #js [])
        prev-val (aget prev-attrs attrs-index)
        prev-node (aget *vnode* index-node)]
    (when (nil? (aget *vnode* index-attrs))
      (aset *vnode* index-attrs prev-attrs))
    (aset *vnode* index-attrs-count (inc attrs-index))
    (when (not= prev-val val)
      (aset prev-attrs attrs-index val)
      (set-fn prev-node ns key val))))

(defn- handle-event-handlers [attrs attrs-index key handler f]
  (let [node (aget *vnode* index-node)]
    (when-let [prev-handler (aget attrs attrs-index)]
      (.removeEventListener node key prev-handler false))
    (when handler
      (.addEventListener node key handler false))
    (aset attrs attrs-index handler)
    (aset attrs (inc attrs-index) f)))

(defn- on-impl [key f param1 param2 param3 param-count]
  (let [attrs-index (or (aget *vnode* index-attrs-count) 0)
        prev-attrs (or (aget *vnode* index-attrs) #js [])
        prev-f (aget prev-attrs (inc attrs-index))
        state-ref (aget *component* index-comp-state-ref)]
    (when (nil? (aget *vnode* index-attrs))
      (aset *vnode* index-attrs prev-attrs))
    (aset *vnode* index-attrs-count (+ attrs-index 2 param-count))
    (cond (and (= 0 param-count) (not= prev-f f))
          (let [handler (when (fn? f) (fn [e] (f e state-ref)))]
            (handle-event-handlers prev-attrs attrs-index key handler f))
          (and (= 1 param-count) (or (not= prev-f f)
                                     (not= param1 (aget prev-attrs (+ attrs-index 2)))))
          (let [handler (when (fn? f) (fn [e] (f e state-ref param1)))]
            (handle-event-handlers prev-attrs attrs-index key handler f)
            (aset prev-attrs (+ attrs-index 2) param1))
          (and (= 2 param-count) (or (not= prev-f f)
                                     (not= param1 (aget prev-attrs (+ attrs-index 2)))
                                     (not= param2 (aget prev-attrs (+ attrs-index 3)))))
          (let [handler (when (fn? f) (fn [e] (f e state-ref param1 param2)))]
            (handle-event-handlers prev-attrs attrs-index key handler f)
            (aset prev-attrs (+ attrs-index 2) param1)
            (aset prev-attrs (+ attrs-index 3) param2))
          (and (= 3 param-count) (or (not= prev-f f)
                                     (not= param1 (aget prev-attrs (+ attrs-index 2)))
                                     (not= param2 (aget prev-attrs (+ attrs-index 3)))
                                     (not= param3 (aget prev-attrs (+ attrs-index 4)))))
          (let [handler (when (fn? f) (fn [e] (f e state-ref param1 param2 param3)))]
            (handle-event-handlers prev-attrs attrs-index key handler f)
            (aset prev-attrs (+ attrs-index 2) param1)
            (aset prev-attrs (+ attrs-index 3) param2)
            (aset prev-attrs (+ attrs-index 4) param3)))))

(defn- on [key f]
  (on-impl key f nil nil nil 0))

(defn- on-static [key f]
  (when (and (> *new-node* 0) (fn? f))
    (let [node (aget *vnode* index-node)
          state-ref (aget *component* index-comp-state-ref)]
      (.addEventListener node key (fn [e] (f e state-ref)) false))))

(defn- on1 [key f attr1]
  (on-impl key f attr1 nil nil 1))

(defn- on-static1 [key f attr1]
  (when (and (> *new-node* 0) (fn? f))
    (let [node (aget *vnode* index-node)
          state-ref (aget *component* index-comp-state-ref)]
      (.addEventListener node key (fn [e] (f e state-ref attr1)) false))))

(defn- on2 [key f attr1 attr2]
  (on-impl key f attr1 attr2 nil 2))

(defn- on-static2 [key f attr1 attr2]
  (when (and (> *new-node* 0) (fn? f))
    (let [node (aget *vnode* index-node)
          state-ref (aget *component* index-comp-state-ref)]
      (.addEventListener node key (fn [e] (f e state-ref attr1 attr2)) false))))

(defn- on3 [key f attr1 attr2 attr3]
  (on-impl key f attr1 attr2 attr3 3))

(defn- on-static3 [key f attr1 attr2 attr3]
  (when (and (> *new-node* 0) (fn? f))
    (let [node (aget *vnode* index-node)
          state-ref (aget *component* index-comp-state-ref)]
      (.addEventListener node key (fn [e] (f e state-ref attr1 attr2 attr3)) false))))

(defn- attr-ns [ns key val]
  (attr-impl ns key (when (not (nil? val)) (str val)) set-attribute))

(defn- attr-ns-static [ns key val]
  (when (and (> *new-node* 0) (not (nil? val)))
    (let [node (aget *vnode* index-node)]
      (set-attribute node ns key (str val)))))

(defn- prop [key val]
  (if (> *svg-namespace* 0)
    (attr-impl nil key (when (not (nil? val)) (str val)) set-attribute)
    (attr-impl nil key val set-property)))

(defn- prop-static [key val]
  (when (and (> *new-node* 0) (not (nil? val)))
    (let [node (aget *vnode* index-node)]
      (if (> *svg-namespace* 0)
        (set-attribute node nil key (str val))
        (set-property node nil key val)))))

(defn- input-value [val]
  (attr-impl nil "value" (when (not (nil? val)) (str val)) set-input-value))

(defn- style [key val]
  (attr-impl nil key (str val) set-style))

(defn- style-static [key val]
  (when (and (> *new-node* 0) (not (nil? val)))
    (let [node (aget *vnode* index-node)]
      (set-style node nil key (str val)))))

(defn- style-custom [key val]
  (attr-impl nil key (str val) set-style-custom))

(defn- style-custom-static [key val]
  (when (and (> *new-node* 0) (not (nil? val)))
    (let [node (aget *vnode* index-node)]
      (set-style-custom node nil key (str val)))))

(defn- call-did-mount-hooks [i]
  (when (> i -1)
    (let [vnode (aget *components-queue* i)
          component (if (component? vnode) vnode (aget vnode index-component))
          props (aget component index-comp-props)
          state-ref (aget component index-comp-state-ref)]
      (set! *vnode* vnode)
      ((aget *components-queue* (dec i)) props state-ref))
    (recur (- i 2))))

;; vnode is nil on first render
(defn- patch-impl [render-queue parent-vnode vnode patch-fn maybe-props]
  (set! moved-flag #js [])
  (when vnode
    (aset parent-vnode index-children-count
          (aget vnode index-comp-data index-comp-data-index-in-parent)))
  (binding [*vnode* parent-vnode
            *component* nil
            *new-node* 0
            *props* nil
            *state* nil
            *moved-vnode* nil
            *skip* false
            *svg-namespace* (if vnode
                              (aget vnode index-comp-data index-comp-data-svg-namespace)
                              0)
            *component-depth* (if vnode
                                (aget vnode index-comp-data index-comp-data-depth)
                                0)
            *watch-local-state* false
            *components-queue-count* 0
            *render-queue* render-queue]
    (if (identical? maybe-props no-props-flag)
      (patch-fn (when vnode (aget vnode index-key)))
      (patch-fn (when vnode (aget vnode index-key)) maybe-props))
    (aset parent-vnode index-children-count 0)
    (set! *watch-local-state* true)
    (call-did-mount-hooks (dec *components-queue-count*))))

(defn- process-render-queue [render-queue]
  (let [l (.-length render-queue)]
    (loop [i 1]
      (when (< i l)
        (let [dirty-comps (aget render-queue i)
              l (if dirty-comps (.-length dirty-comps) 0)]
          (loop [j (dec l)]
            (when (> j -1)
              (let [vnode (.pop dirty-comps)
                    comp-fn (.pop dirty-comps)]
                (when (dirty-component? vnode)
                  (patch-impl render-queue
                              (aget vnode index-parent-vnode) vnode
                              comp-fn (aget vnode index-comp-props))))
              (recur (- j 2)))))
        (recur (inc i)))))
  (aset render-queue 0 nil))

(defn- patch-root-impl [root patch-fn props]
  ;; On first render, render synchronously
  (if-let [children (aget root 0 index-children)]
    (let [render-queue (aget root 1)]
      (aset render-queue 0 true)
      (.requestAnimationFrame js/window
       (fn []
         (patch-impl (aget root 1)
                     (aget children 0 index-parent-vnode) (aget children 0)
                     patch-fn props)
         (process-render-queue render-queue))))
    (patch-impl (aget root 1) (aget root 0) nil patch-fn props)))

(defn patch
  ([vtree patch-fn]
   (patch-root-impl vtree patch-fn no-props-flag))
  ([vtree patch-fn props]
   (patch-root-impl vtree patch-fn props)))

(defn vtree-init [node]
  (assert (and (goog/isObject node) (= 1 (.-nodeType node))) "can only patch dom nodes")
  ;; root node + render queue
  #js [#js [nil nil node nil 0 nil] #js []])

(defn vtree-remove-node [vtree]
  (when-let [children (aget vtree 0 index-children)]
    (remove-real-node (aget children 0))))


;; node identity is the same implies that the svg-namespace value did not change
