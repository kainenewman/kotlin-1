import Foundation
import Stacktrace

func testStackTrace() throws {
    let trace = StacktraceKt.getStackTrace()
    try assertTrue(trace[0].contains("Throwable.kt"))
    try assertTrue(trace[1].contains("Exceptions.kt"))
    try assertTrue(trace[2].contains("stacktrace.kt:7"))
    try assertTrue(trace[3].contains("stacktrace.kt:12"))
    try assertTrue(trace[5].contains("stacktrace.swift:4"))
    try assertTrue(trace[6].contains("main.swift"))
}

class StacktraceTests : TestProvider {
    var tests: [TestCase] = []

    init() {
        providers.append(self)
        tests = [
            TestCase(name: "Stacktrace", method: withAutorelease(testStackTrace)),
        ]
    }
}