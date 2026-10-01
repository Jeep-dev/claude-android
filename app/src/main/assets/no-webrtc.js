// Runs before any page script, in every frame: WebRTC peer connections are removed, so a page
// cannot open direct connections (which can go around a proxy and reveal IP addresses).
// The microphone (getUserMedia) is not WebRTC peer connection and still works.
(() => {
  const NAMES = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel',
    'RTCSessionDescription', 'RTCIceCandidate', 'RTCRtpSender', 'RTCRtpReceiver',
    'RTCRtpTransceiver', 'RTCDtlsTransport', 'RTCIceTransport', 'RTCSctpTransport',
    'RTCCertificate', 'RTCDTMFSender', 'RTCEncodedAudioFrame', 'RTCEncodedVideoFrame'];
  function scrub(win) {
    try {
      for (const name of NAMES) {
        if (name in win) Object.defineProperty(win, name, { value: undefined, writable: false, configurable: false });
      }
    } catch (e) { /* a cross-origin frame scrubs itself */ }
    return win;
  }
  scrub(window);
  // Same-origin frames (about:blank, srcdoc) may start without this script: scrub them on access.
  const frame = HTMLIFrameElement.prototype;
  for (const prop of ['contentWindow', 'contentDocument']) {
    const desc = Object.getOwnPropertyDescriptor(frame, prop);
    if (!desc || !desc.get) continue;
    Object.defineProperty(frame, prop, { configurable: false, enumerable: desc.enumerable, get() {
      const value = desc.get.call(this);
      if (value) scrub(prop === 'contentWindow' ? value : value.defaultView || window);
      return value;
    } });
  }
})();
