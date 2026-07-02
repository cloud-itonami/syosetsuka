(ns syosetsuka.cacao
  "Agent-side CACAO issuance for the kotobase.net tenant Datom plane (JVM).

  The syosetsuka actor mints its OWN server-verifiable CACAO — no
  human-handed token (CLAUDE.md Actors section; JVM exemplar
  ai-gftd-itonami/src/itonami/cacao.clj). This port follows the LIVE tenant
  dialect of kotoba-lang/kami-genko's `kotobase.cacao` (byte-compatible with
  the net-kotobase edge): header t=caip122, resources `kotoba://can/<cap>` +
  `kotoba://graph/<cid>`, domain kotobase.net, DAG-CBOR (canonical key order,
  null statement) then base64.

  Per-actor key model: the actor generates + persists its OWN Ed25519 key
  (`load-or-create-identity!`, .syosetsuka/identity.edn — gitignored, NEVER
  committed). Its writable namespace is `kotobase/db/<did>/<db-name>`; the
  edge derives + pins the canonical graph CID from the CACAO's DID, so a
  self-minted CACAO over the actor's own db is authorized by construction."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream]
           [java.security KeyFactory KeyPairGenerator MessageDigest Signature]
           [java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec]
           [java.time Instant]
           [java.util Base64]))

;; ───────── Ed25519 identity + did:key (itonami exemplar) ─────────

(def ^:private b58 "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn- base58btc [^bytes data]
  (let [zeros (count (take-while zero? data))
        sb (StringBuilder.) fifty8 (java.math.BigInteger/valueOf 58)]
    (loop [n (java.math.BigInteger. 1 data)]
      (when (pos? (.signum n))
        (.append sb (.charAt b58 (.intValue (.mod n fifty8))))
        (recur (.divide n fifty8))))
    (dotimes [_ zeros] (.append sb \1))
    (.toString (.reverse sb))))

(defn- raw-pub
  "Raw 32-byte Ed25519 public key (last 32 bytes of the X.509 SPKI encoding)."
  ^bytes [pub]
  (let [enc (.getEncoded pub)]
    (java.util.Arrays/copyOfRange enc (- (alength enc) 32) (alength enc))))

(defn did-key [pub]
  (let [raw (raw-pub pub)
        framed (byte-array (concat [(unchecked-byte 0xED) (unchecked-byte 0x01)] (seq raw)))]
    (str "did:key:z" (base58btc framed))))

(defn generate-identity
  "A fresh Ed25519 identity {:private-key :public-key :did :private-b64 :public-b64}."
  []
  (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        pub (.getPublic kp)]
    {:private-key (.getPrivate kp) :public-key pub :did (did-key pub)
     :private-b64 (.encodeToString (Base64/getEncoder) (.getEncoded (.getPrivate kp)))
     :public-b64  (.encodeToString (Base64/getEncoder) (.getEncoded pub))}))

(defn load-identity
  [{:keys [private-b64 public-b64]}]
  (let [kf (KeyFactory/getInstance "Ed25519")
        priv (.generatePrivate kf (PKCS8EncodedKeySpec. (.decode (Base64/getDecoder) private-b64)))
        pub  (.generatePublic kf (X509EncodedKeySpec. (.decode (Base64/getDecoder) public-b64)))]
    {:private-key priv :public-key pub :did (did-key pub)
     :private-b64 private-b64 :public-b64 public-b64}))

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
        id))))

;; ───────── canonical graph CID (kotobase.cid port) ─────────

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

(defn- base32-lower-no-pad [^bytes data]
  (let [sb (StringBuilder.)]
    (loop [i 0 buffer 0 bits 0]
      (cond
        (>= bits 5)
        (do (.append sb (.charAt b32 (bit-and (unsigned-bit-shift-right buffer (- bits 5)) 0x1f)))
            (recur i buffer (- bits 5)))

        (< i (alength data))
        (recur (inc i)
               (bit-or (bit-shift-left buffer 8) (bit-and (aget data i) 0xff))
               (+ bits 8))

        :else
        (when (pos? bits)
          (.append sb (.charAt b32 (bit-and (bit-shift-left buffer (- 5 bits)) 0x1f))))))
    (.toString sb)))

(defn- sha256 ^bytes [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn graph-cid-from-name
  "KotobaCid::from_bytes(name).to_multibase(): SHA-256(name) behind a
  CIDv1/dag-cbor/sha2-256 header (0x01 0x71 0x12 0x20), base32-lower 'b'."
  [^String nm]
  (let [hash (sha256 (.getBytes nm "UTF-8"))
        cid (byte-array (concat [(unchecked-byte 0x01) (unchecked-byte 0x71)
                                 (unchecked-byte 0x12) (unchecked-byte 0x20)]
                                (seq hash)))]
    (str "b" (base32-lower-no-pad cid))))

(defn canonical-graph
  "The deterministic graph CID for one of the actor's databases — the edge
  recomputes exactly this from DID + db-name and pins it into every write."
  [did db-name]
  (graph-cid-from-name (str "kotobase/db/" did "/" db-name)))

;; ───────── SIWE message (kotobase dialect, byte-identical) ─────────

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

;; ───────── DAG-CBOR subset (canonical key order; strings/arrays/maps/null) ─────────

(defn- cbor-head [^ByteArrayOutputStream o major n]
  (cond (< n 24)    (.write o (int (+ (bit-shift-left major 5) n)))
        (< n 256)   (do (.write o (int (+ (bit-shift-left major 5) 24))) (.write o (int n)))
        (< n 65536) (do (.write o (int (+ (bit-shift-left major 5) 25)))
                        (.write o (int (bit-and (unsigned-bit-shift-right n 8) 0xff)))
                        (.write o (int (bit-and n 0xff))))
        :else (throw (ex-info "cbor len too big" {:n n}))))

(defn- cbor-key-order
  "DAG-CBOR canonical map key ordering: shorter first, ties bytewise."
  [k]
  (let [s (name k)] [(count (.getBytes ^String s "UTF-8")) s]))

(defn- cbor-val [^ByteArrayOutputStream o v]
  (cond
    (nil? v)        (.write o (int 0xf6))
    (string? v)     (let [b (.getBytes ^String v "UTF-8")]
                      (cbor-head o 3 (alength b)) (.write o b 0 (alength b)))
    (map? v)        (do (cbor-head o 5 (count v))
                        (doseq [[k vv] (sort-by (comp cbor-key-order key) v)]
                          (cbor-val o (name k)) (cbor-val o vv)))
    (sequential? v) (do (cbor-head o 4 (count v)) (doseq [x v] (cbor-val o x)))
    :else           (cbor-val o (str v))))

(defn- cbor-bytes ^bytes [v]
  (let [o (ByteArrayOutputStream.)] (cbor-val o v) (.toByteArray o)))

;; ───────── minting ─────────

(defn- ed-sign ^bytes [priv ^bytes msg]
  (let [s (doto (Signature/getInstance "Ed25519") (.initSign priv))]
    (.update s msg) (.sign s)))

(defn- utc-seconds [^Instant t]
  (str/replace (str t) #"\.\d+Z$" "Z"))

(defn mint-cacao
  "Mint a base64(DAG-CBOR) CACAO for the kotobase.net tenant plane. Returns
  {:cacao-b64 :did :graph}.

  opts: :identity ({:private-key :did} from load-or-create-identity!),
        :aud (operator DID, e.g. did:web:kotobase.net),
        :capability (\"datom:transact\" / \"datom:read\"),
        :extra-capabilities (e.g. [\"tx:create\"]),
        :graph (canonical graph CID),
        :ttl-sec (default 300), :now / :nonce (deterministic test overrides)."
  [{:keys [identity aud capability extra-capabilities graph ttl-sec now nonce]
    :or {ttl-sec 300 extra-capabilities []}}]
  (let [{:keys [private-key did]} identity
        now (or now (Instant/now))
        nonce (or nonce (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 16))
        iat (utc-seconds now)
        exp (utc-seconds (.plusSeconds ^Instant now (long ttl-sec)))
        resources (conj (mapv #(str "kotoba://can/" %) (cons capability extra-capabilities))
                        (str "kotoba://graph/" graph))
        p {:domain "kotobase.net" :iss did :aud aud :version "1"
           :nonce nonce :iat iat :exp exp :statement nil :resources resources}
        msg (cacao-siwe-message p)
        sig (ed-sign private-key (.getBytes ^String msg "UTF-8"))
        sig-b64url (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) sig)
        wire {:h {:t "caip122"}
              :p p
              :s {:t "EdDSA" :s sig-b64url}}]
    {:cacao-b64 (.encodeToString (Base64/getEncoder) (cbor-bytes wire))
     :did did
     :graph graph}))
