(ns helix.core
  (:require [helix.impl.analyzer :as hana]
            [clojure.string :as string]))


;;
;; -- Props
;;

(defn clj->js-obj
  [m {:keys [kv->prop]
      :or {kv->prop (fn [k v] [(name k) v])}}]
  {:pre [(map? m)]}
  (list* (reduce-kv (fn [form k v]
                      `(~@form ~@(kv->prop k v)))
                    '[cljs.core/js-obj]
                    m)))


(defn- camel-case
  "Returns camel case version of the string, e.g. \"http-equiv\" becomes \"httpEquiv\"."
  [s]
  (if (or (keyword? s)
          (string? s)
          (symbol? s))
    (let [[first-word & words] (string/split s #"-")]
      (if (or (empty? words)
              (= "aria" first-word)
              (= "data" first-word))
        s
        (-> (map string/capitalize words)
            (conj first-word)
            string/join)))
    s))

(defn style
  [x]
  (if (map? x)
    (clj->js-obj x {:kv->prop (fn [k v]
                                    [(-> k name camel-case)
                                     (style v)])})
    x))

(defn key->native-prop
  [k v]
  (case k
    :class ["className" v]
    :for ["htmlFor" v]
    :style ["style"
            (if (map? v)
              (style v)
              ;; TODO this needs to camelCase keys
              `(cljs.core/clj->js ~v))]

    [(camel-case (name k)) v]))


(defn primitive?
  [x]
  (or (string? x)
      (number? x)
      (boolean? x)))


(defn props [clj-map native?]
  (let [opts (if native? {:kv->prop key->native-prop} {})]
    (if (contains? clj-map '&)
      `(merge-map+obj ~native?
                      ~(clj->js-obj (dissoc clj-map '&) opts)
                      ~(get clj-map '&))
      (clj->js-obj clj-map opts))))


(defmacro $
  "Create a new React element from a valid React type.

  Will try to statically convert props to a JS object. Falls back to `$$` when
  ambiguous.

  Example:
  ```
  ($ MyComponent
   \"child1\"
   ($ \"span\"
     {:style {:color \"green\"}}
     \"child2\" ))
  ```"
  [type & args]
  (let [native? (or (keyword? type) (string? type))
        type (if native?
               (name type)
               type)]
    (cond
      (map? (first args)) `^js/React.Element (create-element
                                              ~type
                                              ~(props (first args) native?)
                                              ~@(rest args))

      :else `^js/React.Element (create-element ~type nil ~@args))))


(defmacro <>
  "Creates a new React Fragment Element"
  [& children]
  `^js/React.Element ($ Fragment ~@children))


(defn- fnc*
  [display-name props-bindings body]
  (let [ret (gensym "return_value")]
    ;; maybe-ref for react/forwardRef support
    `(fn ^js/React.Element ~display-name
       [props# maybe-ref#]
       (let [~props-bindings [(extract-cljs-props props#) maybe-ref#]]
         (do ~@body)))))


(defmacro defnc
  "Creates a new functional React component. Used like:

  (defnc component-name
    \"Optional docstring\"
    [props ?ref]
    {,,,opts-map}
    ,,,body)

  \"component-name\" will now be a React function component that returns a React
  Element.


  Your component should adhere to the following:

  First parameter is 'props', a map of properties passed to the component.

  Second parameter is optional and is used with `React.forwardRef`.

  'opts-map' is optional and can be used to pass some configuration options to the
  macro. Current options:
   - ':wrap' - ordered sequence of higher-order components to wrap the component in

  'body' should return a React Element."
  [display-name & form-body]
  (let [docstring (when (string? (first form-body))
                    (first form-body))
        props-bindings (if (nil? docstring)
                         (first form-body)
                         (second form-body))
        body (if (nil? docstring)
               (rest form-body)
               (rest (rest form-body)))
        wrapped-name (symbol (str display-name "-helix-render"))
        opts-map? (map? (first body))
        opts (if opts-map?
               (first body)
               {})
        hooks (hana/find-hooks body)
        sig-sym (gensym "sig")
        fully-qualified-name (str *ns* "/" display-name)]
    `(do (when goog/DEBUG
           (def ~sig-sym (signature!)))
         (def ~wrapped-name
           (-> ~(fnc* wrapped-name props-bindings
                                   (cons `(when goog/DEBUG
                                            (when ~sig-sym
                                              (~sig-sym)))
                                         (if opts-map?
                                           (rest body)
                                           body)))
               (cond-> goog/DEBUG
                 (doto (goog.object/set "displayName" ~fully-qualified-name)))
               #_(wrap-cljs-component)
               ~@(-> opts :wrap)))

         (def ~display-name
           ~@(when-not (nil? docstring)
               (list docstring))
           ~wrapped-name)

         (when goog/DEBUG
           (when ~sig-sym
             (~sig-sym ~wrapped-name ~(string/join hooks)
              nil ;; forceReset
              nil)) ;; getCustomHooks
           (register! ~wrapped-name ~fully-qualified-name))
         ~display-name)))


(defn static? [form]
  (boolean (:static (meta form))))

(defn method? [form]
  (and (list? form)
       (simple-symbol? (first form))
       (vector? (second form))))

(defn ->method [[sym-name bindings & form]]
  {:assert [(simple-symbol? sym-name)]}
  (list (str sym-name)
        `(fn ~sym-name ~bindings
           ~@form)))

(defn ->value [[sym-name value]]
  {:assert [(simple-symbol? sym-name)]}
  (list (str sym-name) value))

(defmacro defcomponent
  "Defines a React class component."
  {:style/indent [1 :form [1]]}
  [display-name & spec]
  {:assert [(simple-symbol? display-name)
            (seq (filter #(= 'render (first %)) spec))]}
  (let [[docstring spec] (if (string? (first spec))
                           [(first spec) (rest spec)]
                           [nil spec])
        {statics true spec false} (group-by static? spec)
        js-spec `(cljs.core/js-obj ~@(->> spec
                                (map ->method)
                                (apply concat (list "displayName" (str display-name)))))
        js-statics `(cljs.core/js-obj ~@(->> statics
                                   (map #(if (method? %)
                                           (->method %)
                                           (->value %)))
                                   (apply concat)))]
    ;; TODO handle render specially
    `(def ~display-name (create-component ~js-spec ~js-statics))))

(comment
  (macroexpand
   '(defcomponent asdf
      (foo [] "bar")
      ^:static (greeting "asdf")
      (bar [this] asdf)
      ^:static (baz [] 123)))

  ;; => (helix.core/create-component
  ;;     (cljs.core/js-obj
  ;;      "foo"
  ;;      (clojure.core/fn foo [] "bar")
  ;;      "bar"
  ;;      (clojure.core/fn bar [this] asdf))
  ;;     (cljs.core/js-obj "greeting" "asdf" "baz" (clojure.core/fn baz [] 123)))
  )
