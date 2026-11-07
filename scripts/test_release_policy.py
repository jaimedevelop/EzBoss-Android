import runpy
import subprocess
import sys
import unittest
from pathlib import Path

POLICY = Path(__file__).with_name('check-release.py')
validate = runpy.run_path(str(POLICY))['validate_manifest']
XML = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="pro.ezboss.mobile"><application android:allowBackup="false" android:usesCleartextTraffic="false"><meta-data android:name="pro.ezboss.release.AUTH0_AUDIENCE" android:value="audience"/><meta-data android:name="pro.ezboss.release.API_BASE_URL" android:value="https://api.test"/><meta-data android:name="pro.ezboss.release.AUTH0_CLIENT_ID" android:value="client"/><activity android:name="com.auth0.android.provider.RedirectActivity" android:exported="true"><intent-filter><data android:scheme="pro.ezboss.mobile" android:host="example.auth0.com" android:path="/android/pro.ezboss.mobile/callback"/></intent-filter></activity></application></manifest>'''
class ReleasePolicyTests(unittest.TestCase):
    def check(self, xml, audience='audience'):
        validate(xml, 'pro.ezboss.mobile', 'example.auth0.com', audience, 'https://api.test', 'client')
    def test_valid(self): self.check(XML)
    def test_rejects_unsafe_manifest_and_wrong_build_identity(self):
        for xml in [XML.replace('allowBackup="false"','allowBackup="true"'), XML.replace('android:path=','android:pathPrefix='), XML.replace('usesCleartextTraffic="false"','usesCleartextTraffic="true"'), XML.replace('<application ', '<application android:debuggable="true" '), XML.replace('</application>', '<service android:name="Untrusted" android:exported="true"/></application>')]:
            with self.subTest(xml=xml), self.assertRaises(ValueError): self.check(xml)
        with self.assertRaises(ValueError): self.check(XML, 'other-audience')
    def test_sdk_receiver_requires_exact_platform_permission_and_actions(self):
        receiver = '<receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:exported="true" android:permission="android.permission.DUMP">' + ''.join('<intent-filter><action android:name="androidx.profileinstaller.action.' + action + '"/></intent-filter>' for action in ['INSTALL_PROFILE','SKIP_FILE','SAVE_PROFILE','BENCHMARK_OPERATION']) + '</receiver>'
        valid = XML.replace('</application>', receiver + '</application>')
        self.check(valid)
        with self.assertRaises(ValueError): self.check(valid.replace('android.permission.DUMP', 'android.permission.INTERNET'))
    def test_better_auth_requires_independently_approved_public_topology(self):
        xml = XML.replace('</application>', '<meta-data android:name="pro.ezboss.release.AUTH_PROVIDER" android:value="better-auth"/><meta-data android:name="pro.ezboss.release.AUTH_ORIGIN" android:value="https://alpha.example"/></application>')
        with self.assertRaises(ValueError): self.check(xml)
        validate(xml, 'pro.ezboss.mobile', 'example.auth0.com', 'audience', 'https://api.test', 'client', 'better-auth', 'https://alpha.example')
        for origin in ('https://wrong.example', 'http://alpha.example', None):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                validate(xml, 'pro.ezboss.mobile', 'example.auth0.com', 'audience', 'https://api.test', 'client', 'better-auth', origin)
    def test_optimized_python_still_rejects_invalid_policy(self):
        script = "import runpy; runpy.run_path(%r)['validate_manifest']('<manifest package=\"wrong\"><application/></manifest>', 'pro.ezboss.mobile', 'example.auth0.com')" % str(POLICY)
        result = subprocess.run([sys.executable, '-O', '-c', script], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'Unexpected package', result.stderr)
if __name__ == '__main__': unittest.main()
