def isDeployment() {
    return params.DEPLOYMENT_ID?.trim()
}

def callback(String status, String summary, String endpoint = '') {
    String endpointArgument = endpoint?.trim() ? " --endpoint '${endpoint}'" : ''
    powershell "python '${env.TESTFORGE_PLATFORM_ROOT}/scripts/selftest/jenkins_callback.py' --status '${status}' --summary '${summary}'${endpointArgument}"
}

pipeline {
    agent { label 'testforge-builder' }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
    }

    parameters {
        string(name: 'DEPLOYMENT_ID', defaultValue: '', description: 'TestForge deployment UUID; empty means CI only')
        string(name: 'TARGET_REPOSITORY', defaultValue: '', description: 'Git repository recorded by TestForge')
        string(name: 'COMMIT_SHA', defaultValue: '', description: 'Immutable commit selected by the Test Job')
        string(name: 'TEST_ENVIRONMENT_ID', defaultValue: '', description: 'TestForge environment UUID')
        string(name: 'DEPLOY_ENVIRONMENT', defaultValue: '', description: 'Target environment provider key')
        booleanParam(name: 'INITIALIZE_ENVIRONMENT', defaultValue: false, description: 'Initialize blank environment before deployment')
        string(name: 'CALLBACK_URL', defaultValue: '', description: 'TestForge deployment callback URL')
    }

    stages {
        stage('Resolve Commit') {
            steps {
                deleteDir()
                script {
                    if (isDeployment()) {
                        String requested = params.COMMIT_SHA?.trim()
                        if (!(requested ==~ /[0-9a-fA-F]{40}/)) error('COMMIT_SHA must be a full 40 character hexadecimal Git commit')
                        if (!env.TESTFORGE_SCM_MIRROR_URL?.trim()) error('TESTFORGE_SCM_MIRROR_URL is not configured in Jenkins')
                        powershell "git clone --no-checkout '${env.TESTFORGE_SCM_MIRROR_URL}' .; git fetch --all --tags --prune; git checkout --detach '${requested}'"
                        String head = powershell(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        if (!head.equalsIgnoreCase(requested)) error("Pipeline SCM revision mismatch: workspace=${head}, requested=${requested}")
                        env.RESOLVED_COMMIT = head.toLowerCase()
                    } else if (env.TESTFORGE_SCM_MIRROR_URL?.trim()) {
                        String branch = env.BRANCH_NAME?.trim()
                        if (!(branch ==~ /[0-9A-Za-z._\/-]+/)) error('BRANCH_NAME contains unsupported characters')
                        powershell "git clone --no-checkout '${env.TESTFORGE_SCM_MIRROR_URL}' .; git fetch --all --tags --prune; git checkout '${branch}'"
                        env.RESOLVED_COMMIT = powershell(returnStdout: true, script: 'git rev-parse HEAD').trim().toLowerCase()
                    } else {
                        checkout scm
                        env.RESOLVED_COMMIT = powershell(returnStdout: true, script: 'git rev-parse HEAD').trim().toLowerCase()
                    }
                    env.TESTFORGE_PLATFORM_ROOT = fileExists('testforge-platform/testforge-app') ? 'testforge-platform' : '.'
                }
            }
        }

        stage('Notify Building') {
            when { expression { isDeployment() } }
            steps { script { callback('BUILDING', "TestForge Platform deployment started @ ${env.RESOLVED_COMMIT}") } }
        }

        stage('Initialize Environment') {
            when { expression { isDeployment() && params.INITIALIZE_ENVIRONMENT } }
            steps { powershell '& "$env:TESTFORGE_PLATFORM_ROOT/scripts/selftest/initialize_selftest_environment.ps1" -EnvironmentKey $env:DEPLOY_ENVIRONMENT' }
        }

        stage('Java') {
            steps {
                dir("${env.TESTFORGE_PLATFORM_ROOT}/testforge-app") { powershell '.\\gradlew.bat test verifyModuleBoundaries :app:bootJar --no-daemon' }
            }
        }

        stage('Python Worker') {
            when { expression { !isDeployment() } }
            steps {
                dir("${env.TESTFORGE_PLATFORM_ROOT}/testforge-worker") {
                    powershell '''
                        python -m venv .venv-ci
                        & ./.venv-ci/Scripts/python.exe -m pip install --upgrade pip
                        & ./.venv-ci/Scripts/python.exe -m pip install -e .
                        & ./.venv-ci/Scripts/python.exe -m pytest -q
                    '''
                }
            }
        }

        stage('TestForge MCP') {
            when { expression { !isDeployment() } }
            steps {
                dir("${env.TESTFORGE_PLATFORM_ROOT}/testforge-mcp") {
                    powershell '''
                        go version
                        go test ./...
                        go build -trimpath -o build/testforge-mcp.exe ./cmd/testforge-mcp
                        & ./build/testforge-mcp.exe version
                    '''
                }
            }
        }

        stage('Web Console') {
            steps {
                dir("${env.TESTFORGE_PLATFORM_ROOT}/testforge-client") {
                    powershell 'npm ci; if ($LASTEXITCODE) { exit $LASTEXITCODE }; npm test -- --run; if ($LASTEXITCODE) { exit $LASTEXITCODE }; npm run build'
                }
            }
        }

        stage('Contracts') {
            when { expression { !isDeployment() } }
            steps {
                dir(env.TESTFORGE_PLATFORM_ROOT) { powershell 'python acceptance/validate.py; if ($LASTEXITCODE) { exit $LASTEXITCODE }; python acceptance/evidence/build_index.py --check' }
            }
        }

        stage('Deploy') {
            when { expression { isDeployment() } }
            steps {
                powershell '''
                    & "$env:TESTFORGE_PLATFORM_ROOT/scripts/selftest/deploy_selftest_environment.ps1" `
                      -JarPath "$env:TESTFORGE_PLATFORM_ROOT/testforge-app/app/build/libs/testforge-app.jar" `
                      -ClientDist "$env:TESTFORGE_PLATFORM_ROOT/testforge-client/dist" `
                      -CommitSha $env:COMMIT_SHA `
                      -EnvironmentKey $env:DEPLOY_ENVIRONMENT
                '''
            }
        }

        stage('Notify Ready') {
            when { expression { isDeployment() } }
            steps {
                script {
                    if (!env.TESTFORGE_SELFTEST_ENDPOINT?.trim()) error('TESTFORGE_SELFTEST_ENDPOINT is not configured in Jenkins')
                    callback('READY', "TestForge Platform deployed @ ${env.RESOLVED_COMMIT}", env.TESTFORGE_SELFTEST_ENDPOINT)
                }
            }
        }

        stage('Package') {
            when { expression { !isDeployment() } }
            steps {
                script {
                    archiveArtifacts artifacts: "${env.TESTFORGE_PLATFORM_ROOT}/testforge-app/app/build/libs/testforge-app.jar,${env.TESTFORGE_PLATFORM_ROOT}/testforge-client/dist/**,${env.TESTFORGE_PLATFORM_ROOT}/testforge-mcp/build/testforge-mcp.exe", fingerprint: true
                }
            }
        }
    }

    post {
        always { junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml' }
        failure {
            script {
                if (isDeployment()) {
                    powershell returnStatus: true, script: 'python "$env:TESTFORGE_PLATFORM_ROOT/scripts/selftest/jenkins_callback.py" --status FAILED --summary "TestForge Platform deployment failed"'
                }
            }
        }
    }
}
