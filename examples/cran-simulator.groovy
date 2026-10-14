// Crane simulator: R001 (checkpoint 5005) and R002 (checkpoint 7005), working on several palettes at once.
// Load it, connect both panels, press "Start protocol"; it runs until you press Stop.
//
// SERVER channel (requests from the remote system):
//   in : DD request           LVS1UST1DD..00........3005????<crane>..OK<palette>001
//   out: 44 confirmation      immediately, with the filler fields copied from the DD
// CLIENT channel (crane events to the remote system):
//   out: 16 (palette put on the crane's checkpoint)  -> in: AF      the crane is free again once 16 is sent
//   out: 17 OK (3005)                                -> in: AG
//   out: 18 OK (3005)                                -> in: AH
//   Each message is sent after a random pause (minPause..maxPause).
//   out: 17 DT  refusal of a DD while the crane is busy, or while that palette code is already in progress
//                                                    -> in: AG (with DT)
//
// One session per palette code: its messages keep their order (DD, 44, 16, AF, 17, AG, 18, AH) while sessions
// of both cranes interleave freely. Confirmations are routed to their session by palette code.
//
// Errors (fail the run): DD for an unknown crane, no confirmation within confirmTimeout.
// Warnings (logged, run continues): unexpected data, confirmations nobody waits for.

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

encoding "ASCII"

// ---- configuration --------------------------------------------------------------------------------
def cranes         = [R001: "5005", R002: "7005"]   // crane id -> its checkpoint
def minPause       = 2.s                            // random pause before every 16 / 17 / 18
def maxPause       = 5.s
def confirmTimeout = 30.s                           // max wait for AF / AG / AH
def randomSeed     = null                           // a number makes the pauses repeatable
// ---------------------------------------------------------------------------------------------------

assert minPause.toMillis() <= maxPause.toMillis() : "minPause must not be longer than maxPause"
def rnd = randomSeed == null ? new Random() : new Random(randomSeed as long)
def randomPause = { ->
    pause(minPause.toMillis() + rnd.nextInt((int) (maxPause.toMillis() - minPause.toMillis()) + 1))
}

final lock = new Object()                           // guards busy, sessions, pendingRefusals, stats, session.expecting
def busy = [:]
cranes.each { id, checkpoint -> busy[id] = false }
def sessions = new ConcurrentHashMap()              // palette code -> session
def pendingRefusals = [:]                           // palette code -> number of DT messages awaiting their AG
def stats = [accepted: 0, refused: 0, completed: 0, warnings: 0]

def count = { String key -> synchronized (lock) { stats[key] = stats[key] + 1 } }
def warn = { String text -> count("warnings"); log "WARNING: ${text}" }

// UST1LVS1 <id> <filler: seq, 00, 8 digits> 3005???? <who> 18 <status> <palette> 001
def msg = { String id, String filler, String who, String status, String palette ->
    "UST1LVS1${id}${filler}3005????${who}18${status}${palette}001".toString()
}

def awaitConfirm = { Map s, String type ->
    if (s.box.poll(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS) == null) {
        fail "${s.crane}/${s.palette}: no ${type} confirmation within ${confirmTimeout.toMillis()} ms"
    }
}

def nextStep = { Map s, String type ->             // announce which confirmation is expected, before sending
    synchronized (lock) { s.expecting = type }
}

// one palette on one crane: 16 / AF, 17 / AG, 18 / AH
def runSession = { Map s ->
    randomPause()
    nextStep(s, "AF")
    client.send msg("16", s.filler, s.checkpoint, "OK", s.palette)
    synchronized (lock) { busy[s.crane] = false }
    log "[${s.crane}/${s.palette}] 16 sent, crane is free"
    awaitConfirm(s, "AF")

    randomPause()
    nextStep(s, "AG")
    client.send msg("17", s.filler, "3005", "OK", s.palette)
    log "[${s.crane}/${s.palette}] 17 sent"
    awaitConfirm(s, "AG")

    randomPause()
    nextStep(s, "AH")
    client.send msg("18", s.filler, "3005", "OK", s.palette)
    log "[${s.crane}/${s.palette}] 18 sent"
    awaitConfirm(s, "AH")

    synchronized (lock) {
        sessions.remove(s.palette)
        stats.completed = stats.completed + 1
    }
    log "[${s.crane}/${s.palette}] palette done"
}

// ---- requests from the remote system (server channel) ---------------------------------------------
server.onMessage(~/LVS1UST1DD(..)00(........)3005\?\?\?\?(.{4})..OK(\d{7})001/) { m ->
    def filler = m[1] + "00" + m[2]                 // copied into every answer
    def crane = m[3]
    def palette = m[4]
    def checkpoint = cranes[crane]
    if (checkpoint == null) {
        fail "DD for unknown crane '${crane}' (palette ${palette})"
    }

    boolean accepted
    synchronized (lock) {
        accepted = !busy[crane] && !sessions.containsKey(palette)
        if (accepted) {
            busy[crane] = true
            sessions[palette] = [crane: crane, palette: palette, filler: filler, checkpoint: checkpoint,
                                 box: new LinkedBlockingQueue(), expecting: null]
            stats.accepted = stats.accepted + 1
        } else {
            pendingRefusals[palette] = (pendingRefusals[palette] ?: 0) + 1
            stats.refused = stats.refused + 1
        }
    }

    if (accepted) {
        log "[${crane}/${palette}] DD accepted, crane is busy"
        server.send msg("44", filler, crane, "OK", palette)
        def session = sessions[palette]
        spawn("${crane}/${palette}") { runSession(session) }
    } else {
        log "[${crane}/${palette}] DD refused (crane busy or palette already in progress)"
        client.send msg("17", filler, crane, "DT", palette)
    }
}

server.onUnmatched { m -> warn "unexpected data on the server channel: ${m.text}" }

// ---- confirmations from the remote system (client channel) -----------------------------------------
client.onMessage(~/LVS1UST1(AF|AG|AH)..00........3005\?\?\?\?(.{4})..(OK|DT)(\d{7})001/) { m ->
    def type = m[1]
    def status = m[3]
    def palette = m[4]
    String problem = null
    synchronized (lock) {
        def session = sessions[palette]
        def refusals = pendingRefusals[palette] ?: 0
        if (type == "AG" && refusals > 0 && (status == "DT" || session == null || session.expecting != "AG")) {
            pendingRefusals[palette] = refusals - 1  // confirmation of a refusal
        } else if (session != null && session.expecting == type) {
            session.expecting = null
            session.box.offer(type)
        } else {
            problem = "unexpected ${type} for palette ${palette} (no session waits for it)"
        }
    }
    if (problem) warn problem
}

client.onUnmatched { m -> warn "unexpected data on the client channel: ${m.text}" }

log "Crane simulator running: ${cranes}, pauses ${minPause.toMillis()}..${maxPause.toMillis()} ms. Press Stop to end."
try {
    keepRunning()
} finally {
    synchronized (lock) {
        log "Simulator stopped: ${stats}, still in progress: ${sessions.keySet()}"
    }
}
