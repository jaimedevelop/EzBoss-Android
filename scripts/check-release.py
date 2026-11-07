"""Check the signed APK, decoded merged manifest, exact release callback and export policy.
Requires Android SDK apkanalyzer/apksigner on PATH; no emulator is started.
"""
import argparse
import hashlib
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
def require(condition, message):
    if not condition:
        raise ValueError(message)

A = '{http://schemas.android.com/apk/res/android}'

def validate_manifest(xml, package, domain, audience=None, api=None, client_id=None, auth_provider="auth0", auth_origin=None):
    root = ET.fromstring(xml)
    require(root.get('package') == package, 'Unexpected package')
    app = root.find('application')
    require(app is not None, 'Missing application')
    require(app.get(A + 'allowBackup') == 'false', 'Backup must be explicitly disabled')
    require(app.get(A + 'debuggable', 'false') == 'false', 'Debuggable release')
    require(app.get(A + 'usesCleartextTraffic') == 'false', 'Cleartext must be explicitly disabled')
    require(app.get(A + 'testOnly', 'false') == 'false', 'Test-only build')
    for name, expected in [('AUTH0_AUDIENCE', audience), ('API_BASE_URL', api), ('AUTH0_CLIENT_ID', client_id)]:
        if expected is not None:
            entry = app.find("meta-data[@" + A + "name='pro.ezboss.release." + name + "']")
            require(entry is not None and entry.get(A + 'value') == expected, 'Unexpected release ' + name)
    require(app.get(A + 'networkSecurityConfig') is None, 'Review network security config before release')
    provider = app.find("meta-data[@" + A + "name='pro.ezboss.release.AUTH_PROVIDER']")
    actual_provider = provider.get(A + 'value') if provider is not None else 'auth0'
    require(actual_provider == auth_provider, 'Unexpected authentication provider')
    require(actual_provider in ('auth0', 'better-auth'), 'Unknown authentication provider')
    if actual_provider == 'better-auth':
        from urllib.parse import urlsplit
        entry = app.find("meta-data[@" + A + "name='pro.ezboss.release.AUTH_ORIGIN']")
        require(entry is not None and auth_origin is not None and entry.get(A + 'value') == auth_origin, 'Unexpected auth origin')
        origin = urlsplit(auth_origin)
        require(origin.scheme == 'https' and bool(origin.hostname) and origin.username is None and origin.password is None and not origin.query and not origin.fragment and origin.path in ('','/'), 'Invalid auth origin')
    callback = False
    for component in app:
        if component.tag not in ('activity', 'activity-alias', 'provider', 'receiver', 'service'):
            continue
        name = component.get(A + 'name', '')
        require(component.get(A + 'exported') in ('true', 'false') or not component.findall('intent-filter'), 'Exported policy must be explicit for filtered components')
        exported = component.get(A + 'exported') == 'true'
        if name.endswith('.RedirectActivity'):
            require(exported, 'Callback must be exported')
            data = component.findall('intent-filter/data')
            require(len(data) == 1, 'Callback must have one exact data filter')
            attrs = data[0].attrib
            require(attrs == {A + 'scheme': package, A + 'host': domain, A + 'path': f'/android/{package}/callback'}, 'Unexpected callback identity or wildcard path')
            callback = True
        elif exported and component.tag == 'receiver' and name == 'androidx.profileinstaller.ProfileInstallReceiver':
            require(component.get(A + 'permission') == 'android.permission.DUMP', 'Profile installer must require the platform DUMP permission')
            actions = {x.get(A + 'name') for x in component.findall('intent-filter/action')}
            require(actions == {'androidx.profileinstaller.action.INSTALL_PROFILE', 'androidx.profileinstaller.action.SKIP_FILE', 'androidx.profileinstaller.action.SAVE_PROFILE', 'androidx.profileinstaller.action.BENCHMARK_OPERATION'}, 'Unexpected profile installer action')
            require(not component.findall('intent-filter/data'), 'Unexpected profile installer data filter')
        elif exported:
            require(name in ('.MainActivity', package + '.MainActivity'), 'Unexpected exported component')
            require(component.find('intent-filter/action[@' + A + "name='android.intent.action.MAIN']") is not None, 'Exported activity is not launcher')
        elif component.tag == 'provider':
            require(component.get(A + 'exported') == 'false', 'Provider must explicitly deny export')
    require(callback, 'Missing callback')
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--package', required=True)
    parser.add_argument('--auth0-domain', required=True)
    parser.add_argument('--certificate-sha256', required=True)
    parser.add_argument('--audience', required=True)
    parser.add_argument('--api-origin', required=True)
    parser.add_argument('--client-id', required=True)
    parser.add_argument('--auth-provider', choices=['auth0','better-auth'], default='auth0')
    parser.add_argument('--auth-origin')
    parser.add_argument('--apksigner', default='apksigner')
    parser.add_argument('--apkanalyzer', default='apkanalyzer')
    args = parser.parse_args()
    import re
    require(re.fullmatch(r'[0-9a-fA-F]{64}', args.certificate_sha256.replace(':', '')) is not None, 'Expected certificate must be a SHA-256 fingerprint')
    verification = subprocess.check_output([args.apksigner, 'verify', '--print-certs', str(args.apk)], text=True)
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)', verification)
    require(len(digests) == 1 and digests[0].replace(':', '').lower() == args.certificate_sha256.replace(':', '').lower(), 'Unexpected signing certificate')
    manifest = subprocess.check_output([args.apkanalyzer, 'manifest', 'print', str(args.apk)])
    validate_manifest(manifest, args.package, args.auth0_domain, args.audience, args.api_origin, args.client_id, args.auth_provider, args.auth_origin)
    print('PASS signed APK manifest; sha256=' + hashlib.sha256(args.apk.read_bytes()).hexdigest())
