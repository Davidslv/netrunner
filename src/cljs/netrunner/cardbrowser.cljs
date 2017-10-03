(ns netrunner.cardbrowser
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put! >! sub pub] :as async]
            [netrunner.appstate :refer [app-state]]
            [netrunner.ajax :refer [GET]]))

(def cards-channel (chan))
(def pub-chan (chan))
(def notif-chan (pub pub-chan :topic))

;; Load in sets and mwl lists
(go (let [sets (:json (<! (GET "/data/sets")))
          cycles (:json (<! (GET "/data/cycles")))
          mwl (:json (<! (GET "/data/mwl")))
          latest_mwl (->> mwl
                       (map (fn [e] (update e :date_start #(js/Date %))))
                       (sort-by :date_start)
                       (last))]
      (swap! app-state assoc :sets sets :mwl latest_mwl :cycles cycles)))

(go (let [cards (sort-by :code (:json (<! (GET "/data/cards"))))]
      (swap! app-state assoc :cards cards)
      (swap! app-state assoc :cards-loaded true)
      (put! cards-channel cards)))

(defn make-span [text symbol class]
  (.replace text (js/RegExp. symbol "gi") (str "<span class='anr-icon " class "'></span>")))

(defn image-url [card]
  (str "/img/cards/" (:code card) ".png"))

(defn add-symbols [card-text]
  (-> (if (nil? card-text) "" card-text)
      (make-span "\\[Credits\\]" "credit")
      (make-span "\\[Credit\\]" "credit")
      (make-span "\\[Click\\]" "click")
      (make-span "\\[Subroutine\\]" "subroutine")
      (make-span "\\[Recurring Credits\\]" "recurring-credit")
      (make-span "\\[recurring-credit\\]" "recurring-credit")
      (make-span "1\\[Memory Unit\\]" "mu1")
      (make-span "2\\[Memory Unit\\]" "mu2")
      (make-span "3\\[Memory Unit\\]" "mu3")
      (make-span "\\[Memory Unit\\]" "mu")
      (make-span "1\\[mu\\]" "mu1")
      (make-span "2\\[mu\\]" "mu2")
      (make-span "3\\[mu\\]" "mu3")
      (make-span "\\[mu\\]" "mu")
      (make-span "\\[Link\\]" "link")
      (make-span "\\[Trash\\]" "trash")
      (make-span "\\[adam\\]" "adam")
      (make-span "\\[anarch\\]" "anarch")
      (make-span "\\[apex\\]" "apex")
      (make-span "\\[criminal\\]" "criminal")
      (make-span "\\[hb\\]" "hb")
      (make-span "\\[jinteki\\]" "jinteki")
      (make-span "\\[nbn\\]" "nbn")
      (make-span "\\[shaper\\]" "shaper")
      (make-span "\\[sunny\\]" "sunny")
      (make-span "\\[weyland\\]" "weyland-consortium")
      (make-span "\\[weyland-consortium\\]" "weyland-consortium")))

(defn- card-text
  "Generate text html representation a card"
  [card]
  [:div
   [:h4 (:title card)]
   (when-let [memory (:memoryunits card)]
     (if (< memory 3)
       [:div.anr-icon {:class (str "mu" memory)} ""]
       [:div.heading (str "Memory: " memory) [:span.anr-icon.mu]]))
   (when-let [cost (:cost card)]
     [:div.heading (str "Cost: " cost)])
   (when-let [trash-cost (:trash card)]
     [:div.heading (str "Trash cost: " trash-cost)])
   (when-let [strength (:strength card)]
     [:div.heading (str "Strength: " strength)])
   (when-let [requirement (:advancementcost card)]
     [:div.heading (str "Advancement requirement: " requirement)])
   (when-let [agenda-point (:agendapoints card)]
     [:div.heading (str "Agenda points: " agenda-point)])
   (when-let [min-deck-size (:minimumdecksize card)]
     [:div.heading (str "Minimum deck size: " min-deck-size)])
   (when-let [influence-limit (:influencelimit card)]
     [:div.heading (str "Influence limit: " influence-limit)])
   (when-let [influence (:factioncost card)]
     (when-let [faction (:faction card)]
       [:div.heading "Influence "
        [:span.influence
         {:dangerouslySetInnerHTML #js {:__html (apply str (for [i (range influence)] "&#8226;"))}
          :class                   (-> faction .toLowerCase (.replace " " "-"))}]]))
   [:div.text
    [:p [:span.type (str (:type card))] (if (empty? (:subtype card))
                                                   "" (str ": " (:subtype card)))]
    [:pre {:dangerouslySetInnerHTML #js {:__html (add-symbols (:text card))}}]
    [:div.pack
     (when-let [pack (:setname card)]
       (when-let [number (:number card)]
         (str pack " " number)))]]
   ])

(defn card-view [card owner]
  (reify
    om/IInitState
    (init-state [_] {:showText false})
    om/IRenderState
    (render-state [_ state]
      (sab/html
        [:div.card-preview.blue-shade
         (if (:showText state)
           (card-text card)
           (when-let [url (image-url card)]
             [:img {:src url
                    :onClick #(do (.preventDefault %)
                                (put! (:pub-chan (om/get-shared owner))
                                      {:topic :card-selected :data card})
                                nil)
                    :onError #(-> (om/set-state! owner {:showText true}))
                    :onLoad #(-> % .-target js/$ .show)}]))]))))

(defn card-info-view [card owner]
  (reify
    om/IInitState
    (init-state [_] {:selected-card nil})
    om/IDidMount
    (did-mount [_]
      (let [events (sub (:notif-chan (om/get-shared owner)) :card-selected (chan))]
        (go
          (loop [e (<! events)]
            (om/set-state! owner :selected-card (:data e))
            (recur (<! events))))))
    om/IRenderState
    (render-state [_ {:keys [selected-card]}]
      (sab/html
        (if (nil? selected-card)
          [:div]
          (card-text selected-card))))))

(defn types [side]
  (let [runner-types ["Identity" "Program" "Hardware" "Resource" "Event"]
        corp-types ["Agenda" "Asset" "ICE" "Operation" "Upgrade"]]
    (case side
      "All" (concat runner-types corp-types)
      "Runner" runner-types
      "Corp" (cons "Identity" corp-types))))

(defn factions [side]
  (let [runner-factions ["Anarch" "Criminal" "Shaper"]
        corp-factions ["Jinteki" "Haas-Bioroid" "NBN" "Weyland Consortium" "Neutral"]]
    (case side
      "All" (concat runner-factions corp-factions)
      "Runner" (conj runner-factions "Neutral")
      "Corp" corp-factions)))

(defn options [list]
  (let [options (cons "All" list)]
    (for [option options]
      [:option {:value option :dangerouslySetInnerHTML #js {:__html option}}])))

(defn filter-cards [filter-value field cards]
  (if (= filter-value "All")
    cards
    (filter #(= (field %) filter-value) cards)))

(defn match [query cards]
  (if (empty? query)
    cards
    (filter #(if (= (.indexOf (.toLowerCase (:title %)) query) -1) false true) cards)))

(defn sort-field [fieldname]
  (case fieldname
    "Name" :title
    "Influence" :factioncost
    "Cost" :cost
    "Faction" (juxt :side :faction)
    "Type" (juxt :side :type)
    "Set number" :number))

(defn handle-scroll [e owner {:keys [page]}]
  (let [$cardlist (js/$ ".card-list")
        height (- (.prop $cardlist "scrollHeight") (.innerHeight $cardlist))]
    (when (> (.scrollTop $cardlist) (- height 600))
      (om/update-state! owner :page inc))))

(defn handle-search [e owner]
  (doseq [filter [:set-filter :type-filter :sort-filter :faction-filter]]
    (om/set-state! owner filter "All"))
  (om/set-state! owner :search-query (.. e -target -value)))

(defn card-browser [{:keys [sets cycles] :as cursor} owner]
  (reify
    om/IInitState
    (init-state [this]
      {:search-query ""
       :sort-field "Faction"
       :set-filter "All"
       :type-filter "All"
       :side-filter "All"
       :faction-filter "All"
       :page 1
       :filter-ch (chan)})

    om/IWillMount
    (will-mount [this]
      (go (while true
            (let [f (<! (om/get-state owner :filter-ch))]
              (om/set-state! owner (:filter f) (:value f))))))

    om/IRenderState
    (render-state [this state]
      (.focus (js/$ ".search"))
      (sab/html
       [:div.cardbrowser
        [:div.blue-shade.panel.filters
         (let [query (:search-query state)]
           [:div.search-box
            [:span.e.search-icon {:dangerouslySetInnerHTML #js {:__html "&#xe822;"}}]
            (when-not (empty? query)
              [:span.e.search-clear {:dangerouslySetInnerHTML #js {:__html "&#xe819;"}
                                     :on-click #(om/set-state! owner :search-query "")}])
            [:input.search {:on-change #(handle-search % owner)
                            :type "text" :placeholder "Search cards" :value query}]])

         [:div
          [:h4 "Sort by"]
          [:select {:value (:sort-filter state)
                    :on-change #(om/set-state! owner :sort-field (.trim (.. % -target -value)))}
           (for [field ["Faction" "Name" "Type" "Influence" "Cost" "Set number"]]
             [:option {:value field} field])]]

         (let [cycles-list-all (map #(assoc % :name (str (:name %) " Cycle")
                                            :cycle_position (:position %)
                                            :position 0)
                                    cycles)
               cycles-list (filter #(not (= (:size %) 1)) cycles-list-all)
               ;; Draft is specified as a cycle, but contains no set, nor is it marked as a bigbox
               ;; so we handled it specifically here for formatting purposes
               sets-list (map #(if (not (or (:bigbox %) (= (:name %) "Draft")))
                                  (update-in % [:name] (fn [name] (str "&nbsp;&nbsp;&nbsp;&nbsp;" name)))
                                  %)
                               sets)]
           (for [filter [["Set" :set-filter (map :name
                                                 (sort-by (juxt :cycle_position :position)
                                                          (concat cycles-list sets-list)))]
                         ["Side" :side-filter ["Corp" "Runner"]]
                         ["Faction" :faction-filter (factions (:side-filter state))]
                         ["Type" :type-filter (types (:side-filter state))]]]
             [:div
              [:h4 (first filter)]
              [:select {:value ((second filter) state)
                        :on-change #(om/set-state! owner (second filter) (.. % -target -value))}
               (options (last filter))]]))

         [:div.blue-shade.panel.filters
          (om/build card-info-view nil)
          ]]

        [:div.card-list {:on-scroll #(handle-scroll % owner state)}
         (om/build-all card-view
                       (let [s (-> (:set-filter state)
                                     (.replace "&nbsp;&nbsp;&nbsp;&nbsp;" "")
                                     (.replace " Cycle" ""))
                             cycle-sets (set (for [x sets :when (= (:cycle x) s)] (:name x)))
                             cards (if (= s "All")
                                     (:cards cursor)
                                     (if (= (.indexOf (:set-filter state) "Cycle") -1)
                                       (filter #(= (:setname %) s) (:cards cursor))
                                       (filter #(cycle-sets (:setname %)) (:cards cursor))))]
                         (->> cards
                              (filter-cards (:side-filter state) :side)
                              (filter-cards (:faction-filter state) :faction)
                              (filter-cards (:type-filter state) :type)
                              (match (.toLowerCase (:search-query state)))
                              (sort-by (sort-field (:sort-field state)))
                              (take (* (:page state) 28))))
                       {:key :code})]]))))

(om/root card-browser
         app-state
         {:shared {:notif-chan notif-chan
                   :pub-chan   pub-chan}
          :target (. js/document (getElementById "cardbrowser"))})
