package com.core


def bashScript(script) {
    sh "echo IN BUILD SCRIPT"
    sh "${script}"
}   

def bashScriptReturn(script) {
    return sh(returnStdout: true, script:"${script}")
}

return this