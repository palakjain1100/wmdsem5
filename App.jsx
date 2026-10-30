import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { initializeApp } from 'firebase/app'
import { getAuth, GoogleAuthProvider, onAuthStateChanged, signInWithPopup, signOut } from 'firebase/auth'
import { Activity, Bot, ChevronDown, FileText, LoaderCircle, LogOut, MessageSquarePlus, Plus, Send, Trash2, UserRound } from 'lucide-react'
import questionBank from './questionBank.js'
import './app.css'
import './question-bank.css'

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
}
const auth = firebaseConfig.apiKey && firebaseConfig.projectId ? getAuth(initializeApp(firebaseConfig)) : null
const bundledPdfNames = [
  'Exp-1_D.pdf',
  'Exp-2_D.pdf',
  'Exp-3_D.pdf',
  'Exp-4_D.pdf',
  'Exp-5_D-.pdf',
  'Exp-6_D-.pdf',
  'Exp-7_D-.pdf',
  'Exp-8_M-.pdf',
  'Exp-9_M-.pdf',
  'Exp-10_D-.pdf',
]
const featuredQuestionIds = [
  'exp1-html5',
  'exp2-api',
  'exp3-react',
  'exp4-state',
  'exp5-kotlin',
  'exp6-try-catch-finally',
  'exp7-spring-boot',
]
const featuredQuestions = questionBank.filter((item) => featuredQuestionIds.includes(item.id))
const questionStopWords = new Set([
  'a', 'about', 'an', 'are', 'by', 'can', 'define', 'describe', 'do', 'does', 'explain',
  'for', 'how', 'in', 'is', 'me', 'of', 'please', 'the', 'to', 'tell', 'what', 'when',
  'where', 'which', 'who', 'why', 'with', 'you',
])

function normalizeQuestion(value) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ')
}

function findQuestionBankAnswer(value) {
  const normalized = normalizeQuestion(value)
  const exactMatch = questionBank.find((item) => normalizeQuestion(item.question) === normalized)
  if (exactMatch) return exactMatch

  const queryWords = new Set(normalized.split(' ').filter((word) => word && !questionStopWords.has(word)))
  const matches = questionBank.map((item) => {
    const questionWords = normalizeQuestion(item.question).split(' ').filter((word) => word && !questionStopWords.has(word))
    const matchedWords = questionWords.filter((word) => queryWords.has(word)).length
    return {
      item,
      matchedWords,
      coverage: questionWords.length ? matchedWords / questionWords.length : 0,
      requiredWords: questionWords.length === 1 ? 1 : 2,
    }
  }).filter((match) => match.matchedWords >= match.requiredWords && match.coverage >= 0.75)
    .sort((left, right) => right.matchedWords - left.matchedWords || right.coverage - left.coverage)

  if (matches.length === 0) return null
  if (matches.length > 1 && matches[0].matchedWords === matches[1].matchedWords &&
      matches[0].coverage === matches[1].coverage) return null
  return matches[0].item
}

export default function App() {
  const [user, setUser] = useState(null)
  const [documents, setDocuments] = useState([])
  const [conversations, setConversations] = useState([])
  const [conversationId, setConversationId] = useState(null)
  const [messages, setMessages] = useState([])
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const fileInput = useRef(null)
  const textarea = useRef(null)
  const sidebarPdfs = useMemo(() => [
    ...bundledPdfNames.map((name) => documents.find((doc) => doc.name === name) || {
      id: `library:${name}`, name, status: 'LIBRARY', chunks: 0, library: true,
    }),
    ...documents.filter((doc) => !bundledPdfNames.includes(doc.name)),
  ], [documents])

  const request = useCallback(async (path, options = {}) => {
    const token = await user?.getIdToken()
    const headers = new Headers(options.headers || {})
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
    const response = await fetch(path, { ...options, headers })
    if (!response.ok) {
      let message = `Request failed (${response.status})`
      try { message = (await response.json()).error || message } catch { /* use status */ }
      throw new Error(message)
    }
    return response.status === 204 ? null : response.json()
  }, [user])

  const refresh = useCallback(async () => {
    if (!user) return
    const [docs, chats] = await Promise.all([request('/api/documents'), request('/api/conversations')])
    setDocuments(docs)
    setConversations(chats)
  }, [request, user])

  useEffect(() => {
    if (!auth) { setLoading(false); return undefined }
    return onAuthStateChanged(auth, async (nextUser) => {
      setUser(nextUser)
      setLoading(true)
      setError('')
      if (nextUser) {
        try {
          const token = await nextUser.getIdToken()
          const response = await fetch('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } })
          if (!response.ok) throw new Error('The backend rejected the Firebase sign-in token.')
          const [docs, chats] = await Promise.all([
            fetch('/api/documents', { headers: { Authorization: `Bearer ${token}` } }),
            fetch('/api/conversations', { headers: { Authorization: `Bearer ${token}` } }),
          ])
          if (!docs.ok || !chats.ok) throw new Error('Could not load your workspace.')
          setDocuments(await docs.json())
          setConversations(await chats.json())
        } catch (e) { setError(e.message) }
      } else {
        setDocuments([]); setConversations([]); setMessages([]); setConversationId(null)
      }
      setLoading(false)
    })
  }, [])

  useEffect(() => {
    if (!notice) return undefined
    const timer = setTimeout(() => setNotice(''), 3200)
    return () => clearTimeout(timer)
  }, [notice])

  async function startConversation() {
    setError('')
    if (!user) { setConversationId(null); setMessages([]); return }
    try {
      const chat = await request('/api/conversations', { method: 'POST' })
      setConversationId(chat.id)
      setMessages([])
      setConversations((items) => [chat, ...items.filter((item) => item.id !== chat.id)])
    } catch (e) { setError(e.message) }
  }

  async function openConversation(id) {
    try {
      const chat = await request(`/api/conversations/${encodeURIComponent(id)}`)
      setConversationId(chat.id)
      setMessages(chat.messages.map((message) => ({ ...message, sources: message.sources || [] })))
      setError('')
    } catch (e) { setError(e.message) }
  }

  async function uploadFile(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    if (!user) { setError('Sign in before uploading a PDF.'); return }
    const form = new FormData(); form.append('file', file)
    setBusy(true); setError('')
    try {
      const doc = await request('/api/documents/upload', { method: 'POST', body: form })
      setDocuments((items) => [...items, doc])
      setNotice('PDF uploaded. Choose Index to prepare it for questions.')
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }

  async function deleteDocument(doc) {
    if (!window.confirm(`Delete ${doc.name} and its indexed passages?`)) return
    try {
      await request(`/api/documents/${encodeURIComponent(doc.id)}`, { method: 'DELETE' })
      setDocuments((items) => items.filter((item) => item.id !== doc.id))
      setNotice('Document and its indexed passages deleted.')
    } catch (e) { setError(e.message) }
  }

  async function sendQuestion(value = question) {
    const text = value.trim()
    if (!text || busy) return
    const entry = findQuestionBankAnswer(text)
    setQuestion('')
    setError('')
    setMessages((items) => [...items, {
      id: `answer-${Date.now()}`,
      question: text,
      answer: entry?.answer || 'Answer not found.',
      sources: [],
      demo: Boolean(entry),
      experiment: entry?.experiment,
      topic: entry?.topic,
      timestamp: Date.now(),
    }])
  }

  async function signIn() {
    if (!auth) { setError('Set the VITE_FIREBASE_* values in .env and rebuild the frontend.'); return }
    try { await signInWithPopup(auth, new GoogleAuthProvider()) } catch (e) { setError(e.message) }
  }

  return <div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="#"><span className="brand-mark"><Bot size={17} /></span><span>paper<span className="brand-dot">.</span>trail</span></a>
      <div className="workspace-label">WORKSPACE <span>⌘ K</span></div>
      <button className="nav-active" onClick={startConversation}><MessageSquarePlus size={15} /> New conversation</button>
      {user && <>
        <div className="sidebar-divider" />
        <div className="section-heading"><span>RECENT CHATS</span><span className="count-pill">{conversations.length}</span></div>
        <div className="doc-list">{conversations.map((chat) => <button className="nav-button" key={chat.id} onClick={() => openConversation(chat.id)} title={chat.title}><MessageSquarePlus size={13} /><span className="doc-name">{chat.title}</span></button>)}</div>
      </>}
      <div className="sidebar-divider" />
      <div className="section-heading"><span>ALL PDFS</span><span className="count-pill">{sidebarPdfs.length}</span></div>
      <div className="doc-list">{sidebarPdfs.map((doc) => <div className="doc-row" key={doc.id} title={`${doc.name} — ${doc.status}`}>
        <span className="pdf-icon"><FileText size={13} /></span>
        <span className="doc-name">{doc.name}</span>
        {doc.status === 'INDEXED' ? <span className="doc-pages">{doc.chunks}</span> : <span className="doc-pages">{doc.library ? 'PDF' : doc.status}</span>}
        {user && !doc.library && doc.status !== 'INDEXING' && <button className="doc-action" aria-label={`Delete ${doc.name}`} onClick={() => deleteDocument(doc)}><Trash2 size={12} /></button>}
      </div>)}</div>
      <input ref={fileInput} type="file" accept="application/pdf,.pdf" hidden onChange={uploadFile} />
      <button className="add-document" onClick={() => user ? fileInput.current?.click() : signIn()} disabled={busy}><Plus size={14} /> Add a PDF</button>
      <div className="pdf-storage-note">PDFs are saved on this computer in <code>data/uploads</code>. Sign in to add one.</div>
      <div className="sidebar-bottom">
        <div className="status-indicator"><span className={`status-dot ${error ? 'offline' : ''}`} /><span>{loading ? 'Loading workspace' : error ? 'Needs attention' : 'Ready'}</span></div>
        {user ? <button className="profile" onClick={() => signOut(auth)} title="Sign out"><span className="avatar">{user.displayName?.[0] || <UserRound size={14} />}</span><span className="profile-info"><b>{user.displayName || 'Signed in'}</b><small>Google account</small></span><LogOut size={14} /></button> : <button className="signin-button" onClick={signIn}><UserRound size={14} /> Sign in with Google</button>}
      </div>
    </aside>
    <main className="main-content">
      <header className="topbar"><div className="breadcrumb">Workspace <span>/</span> <b>{conversationId ? conversations.find((chat) => chat.id === conversationId)?.title || 'Conversation' : 'New conversation'}</b></div><div className="top-actions"><span className="model-label"><span className="model-dot" /> QUESTION BANK · CHAT</span></div></header>
      <div className="content-wrap">
        {messages.length === 0 ? <section className="welcome">
          <div className="eyebrow"><span className="eyebrow-line" /> YOUR DOCUMENTS, IN CONTEXT</div>
          <h1>Ask what<br />you <span>want to know.</span></h1>
          <p className="welcome-copy">Ask a question from the question bank. Questions without a matching answer will be marked as not found.</p>
          <div className="suggestion-label">CLICK A QUESTION TO ASK</div>
          <div className="suggestions">
            {featuredQuestions.map((item, index) => (
              <div className="question-card" key={item.id}>
                <button className="suggestion" onClick={() => sendQuestion(item.question)}>
                  <span className={`suggestion-number n${(index % 3) + 1}`}>0{index + 1}</span>
                  <span>{item.question}</span>
                </button>
              </div>
            ))}
          </div>
          {!user && <div className="signin-hint"><UserRound size={14} /><span>Sign in to upload and manage PDFs.</span><button onClick={signIn}>Sign in <span>→</span></button></div>}
        </section> : <section className="conversation">{messages.map((message) => <React.Fragment key={message.id}>
          <div className="message user"><div className="message-body"><div className="message-label">YOU</div><div className="message-text">{message.question}</div></div><div className="user-mark"><UserRound size={15} /></div></div>
          <div className="message assistant"><div className="message-body">
            <div className="message-label">{message.demo ? `PREDEFINED ANSWER · ${message.experiment} · ${message.topic}` : 'ANSWER'}</div>
            {message.pending ? <div className="thinking"><span /><span /><span /> Searching your documents</div> : <div className="message-text">{message.answer}</div>}
            {!message.demo && message.sources?.length > 0 && <div className="sources"><div className="sources-head"><span>SOURCES</span><span>{message.sources.length} passages</span></div>{message.sources.map((source, index) => <details className="source-card" key={`${source.documentId}-${source.page}-${index}`}><summary><span className="source-index">[{index + 1}]</span><span className="source-title">{source.document}</span><span className="source-page">PAGE {source.page}</span><ChevronDown size={13} /></summary><p>{source.text}</p></details>)}</div>}
          </div><div className="assistant-mark"><Bot size={15} /></div></div>
        </React.Fragment>)}</section>}
      </div>
      <div className="composer-wrap"><div className="composer"><textarea ref={textarea} rows={1} value={question} onChange={(event) => setQuestion(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendQuestion() } }} placeholder="Ask a question…" disabled={busy} /><div className="composer-bottom"><span className="composer-hint"><span className="kbd kbd-shift">Shift</span><span className="kbd">↵</span></span><button className="send-button" aria-label="Send question" disabled={!question.trim() || busy} onClick={() => sendQuestion()}>{busy ? <LoaderCircle className="spin" size={15} /> : <Send size={14} />}</button></div></div><div className="disclaimer">Answers come from the question bank. Unmatched questions return “Answer not found.”</div></div>
      {error && <div className="toast error-toast" role="alert"><Activity size={13} />{error}<button onClick={() => setError('')}>×</button></div>}
      {notice && <div className="toast" role="status">{notice}</div>}
      {busy && <div className="busy-banner"><LoaderCircle size={13} className="spin" /> Working…</div>}
    </main>
  </div>
}
