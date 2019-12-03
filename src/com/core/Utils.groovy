package com.core


def bashScript(script) {
    sh "echo IN BUILD SCRIPT"
    sh "${script}"
}   

def String bashScriptReturn(script) {
    return sh(returnStdout: true, script:"${script}")
}

