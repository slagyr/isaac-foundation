(ns isaac.component.registry)

(def ^:dynamic *registry*
  (atom {:instances     {}
         :registrations {}}))

(defn- ->name [x]
  (cond
    (string? x)  x
    (keyword? x) (name x)
    :else        (str x)))

(defn register-instance!
  [component-id instance]
  (swap! *registry* assoc-in [:instances (->name component-id)] instance))

(defn deregister-instance!
  [component-id]
  (swap! *registry* update :instances dissoc (->name component-id)))

(defn instance-for [component-id]
  (get-in @*registry* [:instances (->name component-id)]))

(defn register!
  "Register a component with a component. Used when a config slice loads
   (child isaac-bju6); the component holds registrations until start."
  [component-id registration]
  (let [n (->name component-id)]
    (swap! *registry* update-in [:registrations n]
           (fnil conj #{})
           registration)
    registration))

(defn deregister!
  [component-id registration]
  (let [n (->name component-id)]
    (swap! *registry* update-in [:registrations n]
           (fn [s] (disj (or s #{}) registration)))))

(defn registrations-for [component-id]
  (get-in @*registry* [:registrations (->name component-id)] #{}))

(defn fresh-registry
  ([] (fresh-registry {}))
  ([instances]
   {:instances     instances
    :registrations {}}))

(defn snapshot []
  @*registry*)