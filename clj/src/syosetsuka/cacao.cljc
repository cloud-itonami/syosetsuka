(ns syosetsuka.cacao
  "Agent-side CACAO issuance for the kotobase.net tenant Datom plane —
  **pure .cljc** (JVM / cljs / SCI / WASM portable, ADR-0001 host-caps
  doctrine).

  The syosetsuka actor mints its OWN server-verifiable CACAO — no
  human-handed token (CLAUDE.md Actors section). The dialect is the LIVE
  tenant plane of kotoba-lang/kami-genko's `kotobase.cacao` (byte-compatible
  with the net-kotobase edge): header t=caip122, resources
  `kotoba://can/<cap>` + `kotoba://graph/<cid>`, domain kotobase.net,
  DAG-CBOR (canonical key order, null statement) then base64.

  Portability layout:
  - bytes are plain vectors of ints 0-255 — no ^bytes / Uint8Array in the
    core. SHA-256 is the host-crypto-free `sha256d.core` (com-junkawasaki/
    sha256d-clj). base58btc / base32 / DAG-CBOR / SIWE / CID are pure.
  - the ONLY host capability is the Ed25519 signature: an identity is
    {:did :pub-raw :sign-fn} where :sign-fn is (fn [msg-bytes] -> sig-bytes)
    over int vectors. `identity-from-signer` builds one from any host signer
    (cljs: @noble/curves; SCI/WASM: a host fn). The :clj branch ships JDK
    constructors (`generate-identity` / `load-or-create-identity!`) so the
    JVM works out of the box.

  Per-actor key model: the actor's writable namespace is
  `kotobase/db/<did>/<db-name>`; the edge derives + pins the canonical graph
  CID from the CACAO's DID, so a self-minted CACAO over the actor's own db
  is authorized by construction. Persisted keys (.syosetsuka/identity.edn)
  are gitignored — NEVER commit them."
  (:require [clojure.string :as str]
            [sha256d.core :as sha]
            #?(:clj [clojure.edn :as edn]))
  #?(:clj (:import [java.security KeyFactory KeyPairGenerator Signature]
                   [java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec]
                   [java.time Instant]
                   [java.util Base64])))

;; ───────── bytes (vectors of ints 0-255) ─────────

(def str->bytes sha/str->bytes)

(defn bytes->base64
  "Base64 of an int-vector."
  [bs]
  #?(:clj (.encodeToString (Base64/getEncoder)
                           (byte-array (map unchecked-byte bs)))
     :cljs (js/btoa (apply str (map char bs)))))

(defn bytes->base64url
  "Base64url (no padding) of an int-vector — the pod's CACAO signature
  encoding (URL_SAFE_NO_PAD)."
  [bs]
  (-> (bytes->base64 bs)
      (str/replace "+" "-")
      (str/replace "/" "_")
      (str/replace #"=+$" "")))

;; ───────── base58btc / base32 (pure, bignum-free) ─────────

(def ^:private b58-alphabet "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn- divmod-256
  "Divide a big-endian base-256 digit vector by `divisor`;
  returns [quotient-digits remainder]. Pure long arithmetic."
  [digits divisor]
  (loop [i 0 q [] r 0]
    (if (= i (count digits))
      [(vec (drop-while zero? q)) r]
      (let [acc (+ (* r 256) (nth digits i))]
        (recur (inc i) (conj q (quot acc divisor)) (rem acc divisor))))))

(defn base58btc [bs]
  (let [zeros (count (take-while zero? bs))]
    (loop [ds (vec (drop-while zero? bs)) out ()]
      (if (empty? ds)
        (apply str (concat (repeat zeros \1) out))
        (let [[q r] (divmod-256 ds 58)]
          (recur q (cons (nth b58-alphabet r) out)))))))

(def ^:private b32-alphabet "abcdefghijklmnopqrstuvwxyz234567")

(defn base32-lower-no-pad [bs]
  (let [n (count bs)]
    (loop [i 0 buffer 0 bits 0 out ""]
      (cond
        (>= bits 5)
        (recur i buffer (- bits 5)
               (str out (nth b32-alphabet
                             (bit-and (unsigned-bit-shift-right buffer (- bits 5)) 0x1f))))

        (< i n)
        (recur (inc i)
               (bit-or (bit-shift-left buffer 8) (bit-and (nth bs i) 0xff))
               (+ bits 8)
               out)

        :else
        (if (pos? bits)
          (str out (nth b32-alphabet
                        (bit-and (bit-shift-left buffer (- 5 bits)) 0x1f)))
          out)))))

;; ───────── did:key + canonical graph CID (pure) ─────────

(defn did-key-from-raw
  "did:key:z<base58btc(0xed 0x01 || pub32)> for a raw 32-byte Ed25519 public
  key (int vector)."
  [pub-raw]
  (str "did:key:z" (base58btc (into [0xed 0x01] pub-raw))))

(defn graph-cid-from-name
  "KotobaCid::from_bytes(name).to_multibase(): SHA-256(name) behind a
  CIDv1/dag-cbor/sha2-256 header (0x01 0x71 0x12 0x20), base32-lower 'b'."
  [nm]
  (str "b" (base32-lower-no-pad
            (into [0x01 0x71 0x12 0x20] (sha/sha256-bytes (str->bytes nm))))))

(defn canonical-graph
  "The deterministic graph CID for one of the actor's databases — the edge
  recomputes exactly this from DID + db-name and pins it into every write."
  [did db-name]
  (graph-cid-from-name (str "kotobase/db/" did "/" db-name)))

;; ───────── SIWE message (kotobase dialect, byte-identical, pure) ─────────

(defn cacao-siwe-message
  [{:keys [domain iss aud version nonce iat exp statement resources]}]
  (let [parts (str/split iss #":")
        address (or (last parts) iss)
        chain-id (if (str/starts-with? iss "did:key:")
                   "1"
                   (let [n (count parts)] (if (>= n 2) (nth parts (- n 2)) "1")))
        lines (cond-> [(str (or domain "") " wants you to sign in with your Ethereum account:")
                       address
                       ""]
                statement (conj statement "")
                :always (conj (str "URI: " aud)
                              (str "Version: " version)
                              (str "Chain ID: " chain-id)
                              (str "Nonce: " nonce)
                              (str "Issued At: " iat))
                exp (conj (str "Expiration Time: " exp))
                (seq resources) (as-> ls (apply conj ls "Resources:"
                                                (map #(str "- " %) resources))))]
    (str/join "\n" lines)))

;; ───────── DAG-CBOR subset (canonical key order; pure) ─────────

(defn- cbor-head [major n]
  (cond (< n 24)    [(+ (bit-shift-left major 5) n)]
        (< n 256)   [(+ (bit-shift-left major 5) 24) n]
        (< n 65536) [(+ (bit-shift-left major 5) 25)
                     (bit-and (unsigned-bit-shift-right n 8) 0xff)
                     (bit-and n 0xff)]
        :else (throw (ex-info "cbor len too big" {:n n}))))

(defn- cbor-key-order
  "DAG-CBOR canonical map key ordering: shorter first, ties bytewise."
  [k]
  (let [s (name k)] [(count (str->bytes s)) s]))

(defn cbor-bytes
  "Encode strings / sequentials / maps / nil as canonical DAG-CBOR — an
  int-vector of bytes."
  [v]
  (cond
    (nil? v)        [0xf6]
    (string? v)     (let [bs (str->bytes v)] (into (cbor-head 3 (count bs)) bs))
    (map? v)        (reduce (fn [out [k vv]]
                              (-> out
                                  (into (cbor-bytes (name k)))
                                  (into (cbor-bytes vv))))
                            (cbor-head 5 (count v))
                            (sort-by (comp cbor-key-order key) v))
    (sequential? v) (reduce (fn [out x] (into out (cbor-bytes x)))
                            (cbor-head 4 (count v))
                            v)
    :else           (cbor-bytes (str v))))

;; ───────── identity (host signer injected) ─────────

(defn identity-from-signer
  "Build a portable identity from any host Ed25519 signer.
  pub-raw: raw 32-byte public key (int vector).
  sign-fn: (fn [msg-bytes] -> sig-bytes) over int vectors."
  [pub-raw sign-fn]
  {:did (did-key-from-raw pub-raw)
   :pub-raw (vec pub-raw)
   :sign-fn sign-fn})

#?(:clj
   (defn- jdk-identity [priv pub priv-b64 pub-b64]
     (let [enc (.getEncoded pub)
           raw (vec (map #(bit-and % 0xff)
                         (java.util.Arrays/copyOfRange
                          enc (- (alength enc) 32) (alength enc))))
           sign-fn (fn [msg-bytes]
                     (let [s (doto (Signature/getInstance "Ed25519")
                               (.initSign priv))]
                       (.update s (byte-array (map unchecked-byte msg-bytes)))
                       (vec (map #(bit-and % 0xff) (.sign s)))))]
       (assoc (identity-from-signer raw sign-fn)
              :private-b64 priv-b64 :public-b64 pub-b64))))

#?(:clj
   (defn generate-identity
     "A fresh JDK Ed25519 identity {:did :pub-raw :sign-fn :private-b64
     :public-b64}. Other hosts build identities with `identity-from-signer`."
     []
     (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
           priv (.getPrivate kp) pub (.getPublic kp)]
       (jdk-identity priv pub
                     (.encodeToString (Base64/getEncoder) (.getEncoded priv))
                     (.encodeToString (Base64/getEncoder) (.getEncoded pub))))))

#?(:clj
   (defn load-identity
     [{:keys [private-b64 public-b64]}]
     (let [kf (KeyFactory/getInstance "Ed25519")
           priv (.generatePrivate kf (PKCS8EncodedKeySpec.
                                      (.decode (Base64/getDecoder) ^String private-b64)))
           pub (.generatePublic kf (X509EncodedKeySpec.
                                    (.decode (Base64/getDecoder) ^String public-b64)))]
       (jdk-identity priv pub private-b64 public-b64))))

#?(:clj
   (defn load-or-create-identity!
     "Load the actor's persisted Ed25519 identity at `path`, or generate +
     persist one on first run (only b64 key material is stored; the file is
     gitignored — never commit it)."
     [path]
     (let [f (java.io.File. ^String path)]
       (if (.exists f)
         (load-identity (edn/read-string (slurp f)))
         (let [id (generate-identity)
               parent (.getParentFile (.getAbsoluteFile f))]
           (when parent (.mkdirs parent))
           (spit f (pr-str (select-keys id [:private-b64 :public-b64])))
           id)))))

;; ───────── minting (pure given identity/now/nonce) ─────────

(defn utc-now-seconds []
  #?(:clj (str/replace (str (Instant/now)) #"\.\d+Z$" "Z")
     :cljs (str/replace (.toISOString (js/Date.)) #"\.\d{3}Z$" "Z")))

(defn- plus-seconds-iso [iso-s secs]
  #?(:clj (str/replace (str (.plusSeconds (Instant/parse iso-s) (long secs))) #"\.\d+Z$" "Z")
     :cljs (str/replace (.toISOString (js/Date. (+ (.getTime (js/Date. iso-s))
                                                   (* 1000 secs))))
                        #"\.\d{3}Z$" "Z")))

(defn- random-nonce []
  #?(:clj (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 16)
     :cljs (apply str (repeatedly 16 #(rand-nth "abcdefghijklmnopqrstuvwxyz0123456789")))))

(defn mint-cacao
  "Mint a base64(DAG-CBOR) CACAO for the kotobase.net tenant plane. Returns
  {:cacao-b64 :did :graph}. Pure given the identity's :sign-fn.

  opts: :identity ({:did :sign-fn} — identity-from-signer /
        load-or-create-identity!),
        :aud (operator DID, e.g. did:web:kotobase.net),
        :capability (\"datom:transact\" / \"datom:read\"),
        :extra-capabilities (e.g. [\"tx:create\"]),
        :graph (canonical graph CID),
        :ttl-sec (default 300), :now-iso / :nonce (deterministic overrides)."
  [{:keys [identity aud capability extra-capabilities graph ttl-sec now-iso nonce]
    :or {ttl-sec 300 extra-capabilities []}}]
  (let [{:keys [sign-fn did]} identity
        iat (or now-iso (utc-now-seconds))
        exp (plus-seconds-iso iat ttl-sec)
        nonce (or nonce (random-nonce))
        resources (conj (mapv #(str "kotoba://can/" %) (cons capability extra-capabilities))
                        (str "kotoba://graph/" graph))
        p {:domain "kotobase.net" :iss did :aud aud :version "1"
           :nonce nonce :iat iat :exp exp :statement nil :resources resources}
        msg (cacao-siwe-message p)
        sig (sign-fn (str->bytes msg))
        wire {:h {:t "caip122"}
              :p p
              :s {:t "EdDSA" :s (bytes->base64url sig)}}]
    {:cacao-b64 (bytes->base64 (cbor-bytes wire))
     :did did
     :graph graph}))
