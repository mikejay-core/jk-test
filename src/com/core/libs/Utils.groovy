//src/com/core/libs/Utils.groovy

package com.core.libs

def bashScript(script) {
    sh "${script}"
}

def bashScriptReturn(script) {
    return sh(returnStdout: true, script:"${script}")
}

return this
