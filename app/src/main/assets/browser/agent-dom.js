/* App-owned DOM operations. Arguments arrive as JSON; no host bridge or eval. */
(function (action, args) {
  'use strict';
  const cap = (value, n) => String(value || '').slice(0, n);
  const visible = el => {
    if (!(el instanceof Element)) return false;
    const style = getComputedStyle(el), r = el.getBoundingClientRect();
    return style.display !== 'none' && style.visibility !== 'hidden' && r.width > 0 && r.height > 0 && !el.closest('[aria-hidden="true"]');
  };
  const selection = selector => {
    let list;
    try { list = Array.from(document.querySelectorAll(selector)); }
    catch (_) { throw new Error('BROWSER_INVALID_SELECTOR'); }
    const matches = list.filter(visible);
    if (matches.length !== 1) throw new Error(matches.length ? 'BROWSER_AMBIGUOUS_SELECTOR' : 'BROWSER_ELEMENT_NOT_FOUND');
    return matches[0];
  };
  const selectorFor = el => {
    if (el.id && document.querySelectorAll('#' + CSS.escape(el.id)).length === 1) return '#' + CSS.escape(el.id);
    let path = [], node = el;
    for (let i = 0; node && node !== document.documentElement && i < 8; i++, node = node.parentElement) {
      const siblings = node.parentElement ? Array.from(node.parentElement.children).filter(x => x.tagName === node.tagName) : [];
      path.unshift(node.tagName.toLowerCase() + ':nth-of-type(' + (siblings.indexOf(node) + 1) + ')');
    }
    return path.join(' > ');
  };
  const describe = el => {
    const r = el.getBoundingClientRect();
    return {selector: selectorFor(el), tag: el.tagName.toLowerCase(), role: cap(el.getAttribute('role'), 40),
      name: cap(el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.innerText || el.getAttribute('alt'), 180),
      disabled: !!el.disabled, type: cap(el.getAttribute('type'), 40),
      rect: {x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height)}};
  };
  try {
    if (action === 'read') {
      const root = args.selector ? selection(args.selector) : document.body;
      if (!root) throw new Error('BROWSER_NOT_READY');
      const all = Array.from(root.querySelectorAll('a,button,input,textarea,select,[role="button"],[contenteditable="true"]')).filter(visible);
      const text = root.innerText || '';
      return {ok: true, title: cap(document.title, 256), text: cap(text, 16000), truncated: text.length > 16000 || all.length > 72,
        elements: all.slice(0, 72).map(describe), readyState: document.readyState,
        viewport: {width: innerWidth, height: innerHeight, scrollX, scrollY}, untrusted_page_content: true};
    }
    if (action === 'find') {
      const query = String(args.text || '').toLocaleLowerCase();
      if (!query) throw new Error('BROWSER_TEXT_REQUIRED');
      const matches = Array.from(document.querySelectorAll('a,button,label,input,textarea,h1,h2,h3,p,li,td,[role="button"]')).filter(el => visible(el) &&
        String(el.innerText || el.getAttribute('aria-label') || el.getAttribute('placeholder') || '').toLocaleLowerCase().includes(query));
      return {ok: true, count: matches.length, truncated: matches.length > 40, elements: matches.slice(0, 40).map(describe), untrusted_page_content: true};
    }
    if (action === 'click' || action === 'inspect_click') {
      const el = selection(args.selector);
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') throw new Error('BROWSER_ELEMENT_DISABLED');
      const tag = el.tagName.toLowerCase();
      if (['script','style','iframe','object','embed'].includes(tag) || (tag === 'input' && el.type === 'file')) throw new Error('BROWSER_ACTION_UNSUPPORTED');
      const link = el.closest('a[href]');
      const submit = (el instanceof HTMLButtonElement && el.type === 'submit') ||
        (el instanceof HTMLInputElement && ['submit', 'image'].includes(el.type));
      const form = submit ? el.form : null;
      const target = link ? link.target : (form ? (el.formTarget || form.target) : '');
      if (target && !['_self', '_top', '_parent'].includes(target.toLowerCase())) throw new Error('BROWSER_ACTION_UNSUPPORTED');
      if (link && link.hasAttribute('download')) throw new Error('BROWSER_ACTION_UNSUPPORTED');
      if (action === 'inspect_click') {
        const destination = link ? link.href : (form ? (el.hasAttribute('formaction') ? el.formAction : form.action) : '');
        return {ok: true, navigation_url: destination, selector: args.selector};
      }
      el.scrollIntoView({block: 'center', inline: 'nearest'}); el.click();
      return {ok: true, dispatched: true, verified: false, selector: args.selector};
    }
    if (action === 'type') {
      const el = selection(args.selector);
      if (el.disabled || el.readOnly) throw new Error('BROWSER_ELEMENT_DISABLED');
      if (el instanceof HTMLInputElement && ['file','password','hidden','checkbox','radio','submit','reset','button'].includes(el.type)) throw new Error('BROWSER_INPUT_UNSUPPORTED');
      if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) throw new Error('BROWSER_INPUT_UNSUPPORTED');
      const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      el.scrollIntoView({block: 'center'}); el.focus(); setter.call(el, String(args.text));
      el.dispatchEvent(new Event('input', {bubbles: true})); el.dispatchEvent(new Event('change', {bubbles: true}));
      return {ok: true, dispatched: true, verified: el.value === String(args.text), selector: args.selector, characters: String(args.text).length};
    }
    if (action === 'scroll') {
      const before = {x: scrollX, y: scrollY};
      scrollBy({left: Number(args.x || 0), top: Number(args.y || 0), behavior: 'instant'});
      return {ok: true, dispatched: true, verified: before.x !== scrollX || before.y !== scrollY, x: scrollX, y: scrollY};
    }
    if (action === 'wait') {
      const match = args.selector ? (() => {try { return visible(document.querySelector(args.selector)); } catch (_) {throw new Error('BROWSER_INVALID_SELECTOR');}})() : true;
      const text = !args.text || String(document.body ? document.body.innerText : '').includes(String(args.text));
      return {ok: true, matched: match && text && document.readyState !== 'loading', readyState: document.readyState};
    }
    throw new Error('BROWSER_ACTION_UNSUPPORTED');
  } catch (e) { return {ok: false, error: String(e.message).startsWith('BROWSER_') ? e.message : 'BROWSER_DOM_FAILED'}; }
})
