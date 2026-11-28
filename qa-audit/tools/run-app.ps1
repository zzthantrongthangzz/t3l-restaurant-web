$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtime = Join-Path $project 'target/t3l-qa-audit-runtime'
if (!(Test-Path -LiteralPath (Join-Path $runtime 'compose.env'))) { throw 'Initialize the dedicated Docker QA project first.' }
if (!(Test-Path -LiteralPath (Join-Path $runtime 'app-classpath.txt'))) { throw 'Write the fresh Maven classpath to app-classpath.txt first.' }
$env:SPRING_PROFILES_ACTIVE = 'qa'
$env:SERVER_PORT = '18085'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:23307/t3l_restaurant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true&useSSL=false'
$env:SPRING_DATASOURCE_USERNAME = 't3l_audit'
$env:SPRING_DATASOURCE_PASSWORD = 't3l-audit-disposable-db'
$env:SPRING_DATA_MONGODB_URI = 'mongodb://127.0.0.1:27028/t3l_restaurant'
$env:APP_CHAT_VECTOR_URI = ''
$env:APP_CHAT_API_KEY = ''
$env:APP_JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
$env:APP_ADMIN_USERNAME = 'audit-admin'
$env:APP_STAFF_USERNAME = 'audit-staff'
$env:APP_ADMIN_PASSWORD = $env:QA_LOGIN_PASSWORD
$env:APP_STAFF_PASSWORD = $env:QA_LOGIN_PASSWORD
if (!$env:QA_LOGIN_PASSWORD) { throw 'QA_LOGIN_PASSWORD is required.' }
$env:SPRING_MAIL_HOST = '127.0.0.1'
$env:SPRING_MAIL_PORT = '12525'
$env:SPRING_MAIL_USERNAME = 'noreply@qa.invalid'
$env:SPRING_MAIL_PASSWORD = 'qa-sink'
$env:APP_RESERVATION_MAIL_WORKER_ENABLED = 'false'
$env:AUDIT_LOG_FILE = Join-Path $runtime 'app-security.log'
$env:SEPAY_ENABLED = 'true'
$env:SEPAY_DEMO_ENABLED = 'true'
$env:SEPAY_PAYMENT_MODE = 'POLLING'
$env:SEPAY_API_BASE_URL = 'http://127.0.0.1:12526'
$env:SEPAY_API_TOKEN = 'qa-local-sink-only'
$env:SEPAY_BANK_NAME = 'QA'
$env:SEPAY_BANK_ACCOUNT = '0000000000'
$env:SEPAY_ACCOUNT_HOLDER = 'QA ONLY'
$classpath = (Get-Content -LiteralPath (Join-Path $runtime 'app-classpath.txt') -Raw).Trim()
Push-Location -LiteralPath $runtime
try {
  java -cp $classpath t3l.restaurant.t3l_restaurant.T3LRestaurantApplication "--spring.mail.properties[mail.smtp.auth]=false" "--spring.mail.properties[mail.smtp.starttls.enable]=false" "--spring.mail.properties[mail.smtp.starttls.required]=false"
  exit $LASTEXITCODE
} finally {
  Pop-Location
}
