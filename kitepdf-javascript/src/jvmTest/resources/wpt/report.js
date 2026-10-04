/* Logs the count once every promise_test has settled. Not a file of web-platform-tests. */
Promise.all(harness.pending).then(function () { console.log('DONE ' + harness.passed + ' ' + harness.failed); });
