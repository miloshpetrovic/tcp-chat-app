// Groovy protocol script. Load it like a .tcps file and press "Start protocol"
// (both channels must be connected). It is plain Groovy: classes, closures, collections, regex, JSON ... all work.
//
// Channels                   client, server
//   send:                    client.send "text"            exact bytes, no newline added
//                            client.send hex("02 41 03")   raw bytes ([65, 66] works too)
//   wait for data (FIFO):    def m = client.expect "B"     prefix match; consumes only the match
//                            client.expect ~/ID(\d+)/      regex at the start of the buffer; m[1] = group 1
//                            client.expect contains("OK")  anywhere in the buffer
//                            client.expect(any())          or client.receive()
//                            client.expect("B", 3.s)       per-call timeout (also: client.expect(timeout: 3.s, "B"))
//   message `m`:             m.text   m.bytes   m[1]
//   close:                   client.close()
// Script helpers            log "text" (println works too), pause 2.s, fail "reason", timeout 10.s (default wait),
//                            encoding "ASCII", hex("..."), assert <anything>
// Durations                 2.s  500.ms  1.m  (a plain number means milliseconds, "2s" strings work too)
//
// Manager / workers         def a = worker("name") { ... }     create (not started)
//                            def b = spawn("name") { ... }      create and start
//                            parallel(a, b)                     start and wait for all
//                            mailbox("m").put(x) / .take()      pass values between workers
//                            latch(2), waitUntil { condition }
//   Reading rules: workers sharing a channel take turns (fair lock, FIFO), data is never split between them.
//   The first failure anywhere (assert, exception, timeout, mismatch) stops all workers and fails the run.
//
// Handlers (simulate a peer)  server.onMessage("PING") { m -> server.send "PONG" }
//                            client.onMessage(~/GET (\w+);/) { m -> client.send "RES ${m[1]}" }
//                            server.onUnmatched { m -> log "junk: ${m.text}" }
//                            runFor 60.s   /  keepRunning()   (Stop ends a simulation normally)
//   Register handlers at the start of the script. A channel with handlers is read by its dispatcher only
//   (calling expect on it is an error; server.clearHandlers() switches back).

timeout 30.s

def palettes = ["A", "B", "C"]

// the straight-line exchange from the requirements, with a pause in the middle
palettes.eachWithIndex { p, i ->
    client.send "this is message for ${p}"
    def reply = client.expect "ACK ${p}"
    log "iteration ${i}: got ${reply.text}"
    pause 500.ms
}

// two roles at the same time
def clientSide = worker("client-flow") {
    client.send "A"
    client.expect "B"
    pause 2.s
}
def serverSide = worker("server-flow") {
    server.expect "C"
    server.send "D"
}
parallel(clientSide, serverSide)

log "protocol finished"
