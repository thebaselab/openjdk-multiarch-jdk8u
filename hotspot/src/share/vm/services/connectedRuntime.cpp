/*
 * Copyright 2019-2021 Azul Systems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 only, as published by
 * the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.  See the GNU General Public License version 2 for  more
 * details (a copy is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version 2
 * along with this work; if not, write to the Free Software Foundation,Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Azul Systems, Inc., 1600 Plymouth Street, Mountain View,
 * CA 94043 USA, or visit www.azulsystems.com if you need additional information
 * or have any questions.
 *
 */

#include "precompiled.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/systemDictionary.hpp"
#include "memory/oopFactory.hpp"
#include "runtime/arguments.hpp"
#include "runtime/globals.hpp"
#include "runtime/interfaceSupport.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/mutex.hpp"
#include "runtime/virtualspace.hpp"
#include "services/connectedRuntime.hpp"
#include "utilities/align.hpp"
#include "utilities/hash.hpp"
#include "utilities/macros.hpp"

#include "runtime/os.hpp"
#ifdef TARGET_OS_FAMILY_linux
# include "os_linux.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_solaris
# include "os_solaris.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_windows
# include "os_windows.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_aix
# include "os_aix.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_bsd
# include "os_bsd.inline.hpp"
#endif

//TODO: copy-paste from ostream.cpp
#if defined(SOLARIS) || defined(LINUX) || defined(AIX) || defined(_ALLBSD_SOURCE)
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#endif

#if INCLUDE_CRS

#ifdef _MSC_VER // we need variable-length classes, don't use copy-constructors or assignments
#pragma warning( disable : 4200 )
// warning C4800: 'int' : forcing value to bool 'true' or 'false' (performance warning)
#pragma warning( disable : 4800 )
#endif

#define DEBUG 0

#define log_trace(...) if (ConnectedRuntime::_log_level <= ConnectedRuntime::CRS_LOG_LEVEL_TRACE) tty->print_cr(__VA_ARGS__)
#define log_warning(...) if (ConnectedRuntime::_log_level <= ConnectedRuntime::CRS_LOG_LEVEL_WARNING) tty->print_cr(__VA_ARGS__)
#define log_error(...) if (ConnectedRuntime::_log_level <= ConnectedRuntime::CRS_LOG_LEVEL_ERROR) tty->print_cr(__VA_ARGS__)
#define fatal_or_log(logger, ...) do { if (AzCRSFailJVMOnError) { char msg[1024]; \
  jio_snprintf(msg, sizeof(msg)-1, __VA_ARGS__); \
  fatal(msg);} else {logger(__VA_ARGS__);} } while (0)

#if DEBUG
#define DEBUG_EVENT(ss) \
    { \
      ResourceMark rm; \
      tty->print_cr(">>> before " #ss ": _callback=%s", _callback); \
      Symbol *s = SymbolTable::lookup(_callback, strlen(_callback), THREAD); \
      tty->print_cr(">>> symbol=%p", s); \
      s->print_on(tty); \
      tty->print_cr("\n--------"); \
      if (HAS_PENDING_EXCEPTION) { \
        oop exception = PENDING_EXCEPTION; \
        CLEAR_PENDING_EXCEPTION; \
        java_lang_Throwable::print(exception, tty); \
        tty->cr(); \
        java_lang_Throwable::print_stack_trace(exception, tty); \
      } \
      tty->cr(); \
      tty->print_cr("\n========\n"); \
    }
#else
#define DEBUG_EVENT(ss)
#endif

const static int DEFAULT_DELAY_INITIATION = 2*1000; //2 seconds

const static char ARGS_ENV_VAR_NAME[] = "AZ_CRS_ARGUMENTS";
const static char DELAY_INITIATION[] = "delayInitiation";
const static char NOTIFY_FIRST_CALL[] = "notifyFirstCall";
const static char UNLOCK_CRS_ARGUMENT[] = "UnlockExperimentalCRS";
const static char FILE_URL_PREFIX[] = "file:///";
const static char CRS_AGENT_JAR_PATH[] = "/lib/ext/crs-agent.jar";
const static char CRS_AGENT_CLASS_NAME[] = "com.azul.crs.client.Agent001";
const static char CRS_MODE_STR_AUTO[] = "auto";
const static char CRS_MODE_STR_ON[] = "on";
const static char CRS_MODE_STR_OFF[] = "off";
const static char ENABLE_CRS_ARGUMENT[] = "enable";
const static char ENABLE_CRS_TRUE[] = "true";
const static char ENABLE_CRS_FALSE[] = "false";

// numbers from 0 to max are reserved to CrsMessage types
// the negative numbers could be used to identify other entities
// the values shall be in sync with c.a.c.c.Agent001
enum CrsNotificationType {
  CRS_EVENT_TO_JAVA_CALL   = -98, // Is used to trace the first call to a java method to detect a launcher
  CRS_MESSAGE_CLASS_LOAD   = 0,
  CRS_MESSAGE_FIRST_CALL   = 1,
  CRS_MESSAGE_TYPE_COUNT
};

enum CrsMessageBackReferenceId {
  CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD,
  CRS_MESSAGE_BACK_REFERENCE_ID_COUNT
};

volatile bool ConnectedRuntime::_should_notify = false;
volatile bool ConnectedRuntime::_is_init = false;
volatile bool ConnectedRuntime::_crs_engaged = false;
int ConnectedRuntime::_delayInitiation = DEFAULT_DELAY_INITIATION;
Klass* ConnectedRuntime::_agent_klass = NULL;
Klass* ConnectedRuntime::_callback_listener = NULL;
ConnectedRuntime::LogLevel ConnectedRuntime::_log_level = CRS_LOG_LEVEL_NOT_SET;

static char agentAuthArgs[64] = {0};

enum CRS_MODE_ENUM { CRS_MODE_OFF = 0, CRS_MODE_ON, CRS_MODE_AUTO };
static int _crs_mode = CRS_MODE_OFF;
static bool _should_notify_first_call = false;

#define CRS_CMD_BUF_SIZE 1024
#define CRS_CMD_LEN_SIZE 4
#define CRS_CMD_LEN_FMT "%" XSTR(CRS_CMD_LEN_SIZE) "ld"

class CRSCommandListenerThread: public JavaThread {
  static JavaThread* _thread;
  static int _server_socket;
  static int _client_socket;
  static int _connection_secret;
  static volatile jint _should_terminate;
  static struct sockaddr_in _listener_address;
  //XXX: TODO: eliminate static buffers
  //use NEW_C_HEAP_ARRAY instead
  static char _buffer[CRS_CMD_BUF_SIZE];

  static JavaThread* create();
  static const char* process_cmd(const char* cmd);
  static const char* read_message();
  static void write_message(const char* msg);
  static const char* read(size_t msg_size);
  static void write(const char* msg, size_t msg_size);
  static void close_active_connection();

public:
  CRSCommandListenerThread();
  static void thread_entry(JavaThread* thread, TRAPS);

  static void start();
  static void stop();

  bool is_hidden_from_external_view() const { return true; }
  bool is_vm_internal_java_thread() const { return true; }

};

JavaThread* CRSCommandListenerThread::_thread = NULL;
int CRSCommandListenerThread::_server_socket = -1;
int CRSCommandListenerThread::_client_socket = -1;
int CRSCommandListenerThread::_connection_secret = -1;
volatile jint CRSCommandListenerThread::_should_terminate = 0;
struct sockaddr_in CRSCommandListenerThread::_listener_address = {0};
char CRSCommandListenerThread::_buffer[] = {0};

CRSCommandListenerThread::CRSCommandListenerThread() : JavaThread(&thread_entry) {
  log_trace("Initialized CRS Listener thread %p", this);

  _listener_address.sin_family = AF_INET;
  _listener_address.sin_port = htons(0);
  _listener_address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

  int sock = os::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (sock == -1) {
    log_trace("Socket creation error: %s. Communication with the agent interrupted.", strerror(errno));
    return;
  }

  socklen_t addrlen = sizeof _listener_address;
  if (os::bind(sock, (struct sockaddr*) &_listener_address, addrlen) < 0) {
    log_trace("Socket bind error: %s. Communication with the agent interrupted.", strerror(errno));
    os::socket_close(sock);
    return;
  }

  if (getsockname(sock, (struct sockaddr*) &_listener_address, &addrlen) < 0) {
    log_trace("getsockname error: %s. Communication with the agent interrupted.", strerror(errno));
    os::socket_close(sock);
    return;
  }

  _server_socket = sock;
  _connection_secret = os::random();
  jio_snprintf(agentAuthArgs, sizeof(agentAuthArgs) - 1, "agentAuth=%d+%d,", ntohs(_listener_address.sin_port), _connection_secret);
}


void CRSCommandListenerThread::close_active_connection() {
  int agent_socket = _client_socket;
  if (agent_socket > 0) {
    os::socket_close(agent_socket);
  }
  _client_socket = -1;
}

const char* CRSCommandListenerThread::read_message() {
  size_t msg_len = atoi(read(CRS_CMD_LEN_SIZE));
  return read(msg_len);
}

void CRSCommandListenerThread::write_message(const char* msg) {
  ssize_t msg_len = msg == NULL ? 0 : strlen(msg);
  ssize_t len = msg_len;
  for (int i = CRS_CMD_LEN_SIZE - 1; i >= 0; i--) {
    _buffer[i] = '0' + (len % 10);
    len /= 10;
  }
  assert(len == 0, "CRS_CMD_LEN_SIZE cannot fit result_len");
  _buffer[CRS_CMD_LEN_SIZE] = '\0';
  write(_buffer, CRS_CMD_LEN_SIZE);
  write(msg, msg_len);
}

const char* CRSCommandListenerThread::read(size_t msg_len) {
  assert(msg_len < CRS_CMD_BUF_SIZE, "Too long message...");

  ssize_t to_read = msg_len;
  size_t buf_pos = 0;
  ssize_t read;

  while (_client_socket > 0 && to_read > 0) {
    read = recv(_client_socket, &_buffer[buf_pos], MIN2(to_read, (ssize_t)(CRS_CMD_BUF_SIZE - 1)), 0);
    if (read <= 0) {
      log_trace("Connection closed");
      close_active_connection();
      break;
    }
    to_read -= read;
    buf_pos += read;
    if (buf_pos >= CRS_CMD_BUF_SIZE) {
      buf_pos = 0;
    }
  }
  _buffer[buf_pos] = '\0';
  return _buffer;
}

void CRSCommandListenerThread::write(const char* msg, size_t msg_len) {
  assert(msg_len < CRS_CMD_BUF_SIZE, "Too long message...");
  ssize_t to_send = MIN2((ssize_t)msg_len, (ssize_t)(CRS_CMD_BUF_SIZE - 1));
  size_t buf_pos = 0;
  ssize_t sent;

  while (_client_socket > 0 && to_send > 0) {
    sent = send(_client_socket, &msg[buf_pos], to_send, 0);
    if (sent <= 0) {
      log_trace("Connection closed");
      close_active_connection();
      break;
    }
    to_send -= sent;
    buf_pos += sent;
  }
}

void CRSCommandListenerThread::thread_entry(JavaThread* jt, TRAPS) {
  // we are expecting default thread's wx state
  Thread::WXWriteVerifier wx_write;

  ThreadToNativeFromVM ttn(jt);

  log_trace("CRS CommandListener Thread Started");
  do {

    if (_server_socket < 0) {
      break;
    }

    if (os::listen(_server_socket, 1 /*length of connections queue*/) < 0) {
      log_trace("Socket listen error: %s. Communication with the agent interrupted.", strerror(errno));
      os::socket_close(_server_socket);
      break;
    }

    socklen_t addrlen = sizeof _listener_address;

    if (!_should_terminate) {
      if ((_client_socket = os::accept(_server_socket, (struct sockaddr *) &_listener_address, &addrlen)) < 0) {
        log_warning("Socket accept error: %s. Communication with the agent interrupted.", strerror(errno));
        os::socket_close(_server_socket);
        break;
      }

      if (_connection_secret != atoi(read_message())) {
        log_error("Agent has failed authentication. Communication with the agent interrupted.");
        close_active_connection();
        break;
      }

      write_message("OK");
      log_trace("Agent connected.");
    }

    const char* msg;
    const char* result;

    while (_client_socket > 0 && !_should_terminate) {
      msg = read_message();

      {
        ThreadInVMfromNative tfm(jt);
        result = process_cmd(msg);
      }

      write_message(result);
    }
  } while (0);

  close_active_connection();

  log_trace("CRS CommandListener Thread Exited");
}

JavaThread* CRSCommandListenerThread::create() {
  return new CRSCommandListenerThread();
}

typedef JavaThread* (*JavaThreadCreateFunction)();
void initializeAndStart(const char* thread_name, ThreadPriority priority, JavaThreadCreateFunction thread_create);

void CRSCommandListenerThread::start() {
  assert(_thread == NULL, "CRS Listener thread already started");
  initializeAndStart("CRS Listener Thread", MinPriority, &create);
}

void CRSCommandListenerThread::stop() {
  _should_terminate = 1;
}

class CRSAgentInitThread: public JavaThread {
  static JavaThread* _thread;

  static JavaThread* create();

public:
  CRSAgentInitThread();
  static void thread_entry(JavaThread* thread, TRAPS);

  static void start();
};

JavaThread* CRSAgentInitThread::_thread = NULL;

CRSAgentInitThread::CRSAgentInitThread() : JavaThread(&thread_entry) {
  log_trace("Initialized CRS Agent Init thread %p", this);
}

JavaThread* CRSAgentInitThread::create() {
  return new CRSAgentInitThread();
}

void CRSAgentInitThread::thread_entry(JavaThread* jt, TRAPS) {
    os::sleep(jt, ConnectedRuntime::_delayInitiation, true);
    ConnectedRuntime::startAgent(THREAD);
}

void CRSAgentInitThread::start() {
  assert(_thread == NULL, "CRS Agent init thread already started");
  initializeAndStart("CRS Agent init Thread", MinPriority, &create);
}

class CRSConcurrentLinkedList {
public:
  class Item {
    Item* volatile _next;
  public:
    Item(): _next() {}
    Item(Item* next): _next(next) {}
    Item* next() const { return _next; }
    void set_next(Item* i) { _next = i; }
  };
private:
  Item* volatile _list;
  static Item _head_park_marker;
public:
  CRSConcurrentLinkedList(): _list() {}
  void add(Item* i);
  void add_items(Item *l);
  Item* remove();
  Item* head() const { return _list; }
};
CRSConcurrentLinkedList::Item CRSConcurrentLinkedList::_head_park_marker;

void CRSConcurrentLinkedList::add(Item* item) {
  Item *head;
  do {
    head = _list;
    if (head == &_head_park_marker) { continue; }
    item->set_next(head);
    if (Atomic::cmpxchg_ptr(item, &_list, head) == head) { break; }
  } while (true);
}

void CRSConcurrentLinkedList::add_items(Item* items) {
  Item *head;
  // items shall point to Items which are not being modified concurrently
  Item *tail = items;
  while (tail->next()) { tail = tail->next(); }

  do {
    head = _list;
    if (head == &_head_park_marker) { continue; }
    tail->set_next(head);
    if (Atomic::cmpxchg_ptr(items, &_list, head) == head) { break; }
  } while (true);
}

 CRSConcurrentLinkedList::Item *CRSConcurrentLinkedList::remove() {
   Item *head;

  do {
    head = _list;
    if (head == NULL) { return NULL; }
    if (head == &_head_park_marker) { continue; }
    if (Atomic::cmpxchg_ptr(&_head_park_marker, &_list, head) == head) { break; }
  } while (true);

  // _list is protected at this point -- no one can modify it now. We can safely
  // cut off the head, 'unlock' the list, and return the trophy.
  _list = head->next();
  head->set_next(NULL);
  return head;
}

class TLB: public CHeapObj<mtTracing>, public CRSConcurrentLinkedList::Item {
  uintx _pos;
  u1* _base;
  Thread *_owner;
  u1* _reference_message[CRS_MESSAGE_BACK_REFERENCE_ID_COUNT];

public:
  TLB(): _owner(), _base() {}
  u1 *base() const { return _base; }
  void set_base(u1* base) { _base = base; }
  void lease(Thread *thread) {
    assert(!_owner && thread, "sanity");
    _pos = 0;
    _owner = thread;
    for(int i=0; i<CRS_MESSAGE_BACK_REFERENCE_ID_COUNT; i++) _reference_message[i] = NULL;
  }
  void release() { assert(_owner != NULL, "sanity"); _owner = NULL; }
  Thread *owner() const { return _owner; }
  uintx pos() const { return _pos; }
  u1 *reference_message(CrsMessageBackReferenceId back_ref_id) const { return _reference_message[back_ref_id]; }
  u1* alloc(uintx size);
  void set_reference_message(CrsMessageBackReferenceId back_ref_id, u1 *message) { _reference_message[back_ref_id] = message; }
};

class TLBClosure: public Closure {
public:
  virtual void tlb_do(TLB *tlb) = 0;
};

class TLBManager {
  CRSConcurrentLinkedList _free_list;
  CRSConcurrentLinkedList _leased_list;
  CRSConcurrentLinkedList _uncommitted_list;
  TLB *_buffers;
  ReservedSpace _rs;
  uintx _buffer_size;
  jint _num_committed;
  uintx _buffers_count;
  uintx _area_size;
  uintx _bytes_used;
  // temporarily holds the buffers popped from _leased_list during flush
  // need to be accessible because safepoint can happen during flush (only when flushing
  // single buffer) so need to have all buffers which contain the data accessible so
  // the data can be evacuated if metaspace is evicted.
  // does not need atomic access because only accessed by CRS flush thread or
  // inside a safepoint
  TLB *_not_finished;

  TLB *lease_buffer(Thread *thread);
  bool uncommit_buffer(TLB *buffer, TLB **uncommitted_list);
public:
  static const uintx align = sizeof(intptr_t);

  TLBManager(uintx area_size);
  ~TLBManager();
  void flush_buffers(TLBClosure *f, uintx committed_goal);
  uintx bytes_used() const;
  TLB *ensure(TLB* buffer, uintx size, Thread *thread);
  u1 *alloc(TLB* buffer, uintx size);
  uintx bytes_committed() const { return (uintx)_num_committed * _buffer_size; }
  void leased_buffers_do(TLBClosure *f);
};

class NativeMemory: public CHeapObj<mtTracing> {
  TLBManager _tlb_manager;
  uintx _previous_usage; // high usage watermark on previous flush
  bool _overflow;

public:
  NativeMemory(uintx size);
  ~NativeMemory();

  u1 *alloc(CrsMessageBackReferenceId ref_id, bool *is_reference, uintx size, uintx size_reference, Thread *thread);
  u1 *alloc(uintx size, Thread *thread);
  void flush(TRAPS);
  u1 *reference_message(CrsMessageBackReferenceId ref_id, Thread *thread);
  void buffers_do(TLBClosure *f);
  void release_buffers();
  void release_thread_buffer(Thread *thread) const;
  uintx bytes_used() const { return _tlb_manager.bytes_used(); }
};

u1* TLB::alloc(uintx size) {
  assert(_base, "must be initialized");
  u1* ptr = _base + _pos;
  _pos += align_up(size, TLBManager::align);
  return ptr;
}

TLBManager::TLBManager(uintx size): _bytes_used(), _not_finished() {
  // it's known that normal VM startup loads about 2k classes
  // each record takes about 72 bytes (144k)
  // about 11k different methods are executed
  // with record size 24 bytes (264k)
  // some memory is wasted at the time of flush because buffers are in use
  // based on real usage the size estimate is 640k for 64 bit system
  const uintx initialCommittedSizeEstimate = MIN2(LP64_ONLY(640*K) NOT_LP64(512*K), size);
  const uintx desiredBufferSize = 8*K; // so about 128 records in one buffer
  _buffers_count = size / desiredBufferSize;
  if (_buffers_count < 2)
    _buffers_count = 2;
  _buffer_size = align_up(size / _buffers_count, os::vm_page_size());
  if (_buffer_size > 1u<<16) { // the implementation assumes no more than 64k per buffer
    _buffer_size = 1u<<16;
    _buffers_count = size / _buffer_size;
  }
  _num_committed = (jint)MIN2(MAX2((uintx)1u, initialCommittedSizeEstimate / _buffer_size), _buffers_count);
  _area_size = _buffers_count * _buffer_size;
  _buffers = new TLB[_buffers_count];

  _rs = ReservedSpace(_area_size, os::vm_page_size());
  MemTracker::record_virtual_memory_type(_rs.base(), mtTracing);
  if (!os::commit_memory(_rs.base(), _num_committed * _buffer_size, false)) {
    ConnectedRuntime::disable("Unable to allocate CRS native memory buffers", false);
    return;
  }
  os::trace_page_sizes("Crs", _area_size,
                              _area_size,
                              os::vm_page_size(),
                              _rs.base(),
                              _rs.size());
  for (uintx i = 0; i < _buffers_count; i++)
    _buffers[i].set_base(((u1*)_rs.base()) + i * _buffer_size);
  for (intx i = _num_committed; --i >= 0; )
    _free_list.add(_buffers + i);
  for (intx i = (intx)_buffers_count; --i >= _num_committed; )
    _uncommitted_list.add(_buffers + i);
  if (DEBUG) tty->print_cr("allocated %u of %" PRIuPTR " buffers of %" PRIuPTR " size. area size requested %" PRIuPTR " actual %" PRIuPTR " (%p %" PRIxPTR ")",
      _num_committed, _buffers_count, _buffer_size, size, _area_size, _rs.base(), _rs.size());
}

TLBManager::~TLBManager() {
  os::uncommit_memory(_rs.base(), _area_size, !ExecMem);
  _area_size = 0;
  delete _buffers;
  _buffers = NULL;
}

TLB* TLBManager::lease_buffer(Thread *thread) {
  TLB *to_lease;

  // trivial case, try to obtain a buffer
  to_lease = (TLB*)_free_list.remove();
  if (!to_lease) {
    // no free buffers, try to allocate
    to_lease = (TLB*)_uncommitted_list.remove();
    if (to_lease) {
      // successfully got new area, allocate memory for it
      if (!os::commit_memory((char*)to_lease->base(), _buffer_size, false)) {
        // no physical memory, put buffer back
        _uncommitted_list.add(to_lease);
        return NULL;
      }
      Atomic::inc(&_num_committed);
      assert((uintx)_num_committed <= _buffers_count, "sanity");
    } else {
      // no memory available
      if (DEBUG) tty->print_cr("out of buffer space %u buffers committed %" PRIuPTR " bytes used", _num_committed, _bytes_used);
      return NULL;
    }
  }

  to_lease->lease(thread);
  _leased_list.add(to_lease);
  Atomic::add((intx)_buffer_size, (intx*)&_bytes_used);

  if (DEBUG) tty->print_cr("leased buffer %p", to_lease->base());

  return to_lease;
}

uintx TLBManager::bytes_used() const {
  return _bytes_used;
}

TLB *TLBManager::ensure(TLB* buffer, uintx size, Thread *thread) {
  assert(size <= _buffer_size, "size too big");
  if (buffer && _buffer_size - buffer->pos() >= size) {
    return buffer;
  }
  if (buffer) {
    assert(buffer->owner() == Thread::current(), "must be");
    buffer->release();
  }
  return lease_buffer(thread);
}

u1* TLBManager::alloc(TLB* buffer, uintx size) {
  assert(size <= _buffer_size - buffer->pos(), "invariant");
  if (buffer == NULL)
    return NULL;
  u1 *p = buffer->alloc(size);
  assert(p >= (u1*)_rs.base() && p + size <= (u1*)_rs.base() + _rs.size(), "sanity");
  return p;
}

void TLBManager::flush_buffers(TLBClosure* f, uintx committed_goal) {
  TLB *uncommitted = NULL;
  int count_leased = 0, count_released = 0, count_uncommitted = 0;
  committed_goal /= _buffer_size;
  uintx to_uncommit = (uintx)_num_committed > committed_goal ? ((uintx)_num_committed) - committed_goal : 0;
  do {
    TLB *to_flush = static_cast<TLB*>(_leased_list.remove());
    if (!to_flush)
      break;
    Thread *owner = to_flush->owner();
    if (owner)
      count_leased++;
    else
      count_released++;
    if (owner) {
      // not yet finished, do not attempt to flush because more data can be written
      to_flush->set_next(_not_finished);
      _not_finished = to_flush;
    } else {
      // may provoke safepoint which in turn may cause metaspace eviction
      f->tlb_do(to_flush);
      // add buffer to _free_list as soon as it is free
      Atomic::add(-(intx)_buffer_size, (intx*)&_bytes_used);
      if (to_uncommit && uncommit_buffer(to_flush, &uncommitted)) {
        to_uncommit--;
        count_uncommitted++;
      } else {
        _free_list.add(to_flush);
      }
    }
  } while (true);
  // return back all not flushed buffers
  if (_not_finished) {
    _leased_list.add_items(_not_finished);
    _not_finished = NULL;
  }
  while (to_uncommit) {
    TLB *b = static_cast<TLB*>(_free_list.remove());
    if (!b)
      break;
    if (uncommit_buffer(b, &uncommitted)) {
      to_uncommit--;
      count_uncommitted++;
    } else
      break;
  }
  if (uncommitted)
    _uncommitted_list.add_items(uncommitted);
  if (DEBUG) tty->print_cr(" flush leased %d released %d uncommitted %d",
          count_leased, count_released, count_uncommitted);
}

bool TLBManager::uncommit_buffer(TLB* buffer, TLB** uncommitted_list) {
  if (os::uncommit_memory((char*)buffer->base(), _buffer_size, !ExecMem)) {
    buffer->set_next(*uncommitted_list);
    *uncommitted_list = buffer;
    assert(_num_committed > 0, "sanity");
    Atomic::add(-1, &_num_committed);
    return true;
  }
  return false;
}

void TLBManager::leased_buffers_do(TLBClosure* f) {
  // warning, naked operation, caller is assumed to synchronize
  for (TLB *b = static_cast<TLB*>(_leased_list.head()); b; b = static_cast<TLB*>(b->next()))
    f->tlb_do(b);
  // traverse buffers which have been put aside during flush
  for (TLB *b = _not_finished; b; b = static_cast<TLB*>(b->next()))
    f->tlb_do(b);
}

NativeMemory::NativeMemory(uintx size):
_tlb_manager(size), _previous_usage(_tlb_manager.bytes_committed()), _overflow() {}

NativeMemory::~NativeMemory() {
}

u1* NativeMemory::alloc(CrsMessageBackReferenceId back_ref_id, bool *is_reference, uintx size, uintx size_reference, Thread *thread) {
  if (_overflow)
    return NULL;

  TLB *buffer = thread->crs_thread_locals()->buffer();
  TLB *new_buffer = _tlb_manager.ensure(buffer, size, thread);
  if (new_buffer != buffer) {
    thread->crs_thread_locals()->set_buffer(new_buffer);
    *is_reference = true;
  }
  if (new_buffer != NULL) {
    u1 *message = _tlb_manager.alloc(new_buffer, *is_reference ? size_reference : size);
    if (*is_reference)
      new_buffer->set_reference_message(back_ref_id, message);
    return message;
  }
  _overflow = true;
  return NULL;
}

u1* NativeMemory::alloc(uintx size, Thread *thread) {
  if (_overflow)
    return NULL;

  TLB *buffer = thread->crs_thread_locals()->buffer();
  TLB *new_buffer = _tlb_manager.ensure(buffer, size, thread);
  if (new_buffer != buffer) {
    thread->crs_thread_locals()->set_buffer(new_buffer);
  }
  if (new_buffer != NULL) {
    return _tlb_manager.alloc(new_buffer, size);
  }
  _overflow = true;
  return NULL;
}

u1* NativeMemory::reference_message(CrsMessageBackReferenceId ref_id, Thread *thread) {
  TLB *buffer = thread->crs_thread_locals()->buffer();
  return buffer ? buffer->reference_message(ref_id) : NULL;
}

void NativeMemory::buffers_do(TLBClosure* f) {
  _tlb_manager.leased_buffers_do(f);
}

void NativeMemory::release_thread_buffer(Thread *thread) const {
  assert(Thread::current() == thread || SafepointSynchronize::is_at_safepoint(), "sanity");

  TLB *buffer = thread->crs_thread_locals()->buffer();
  if (buffer) {
    buffer->release();
    thread->crs_thread_locals()->set_buffer(NULL);
  }
}

static class CRSEvent: public ResourceObj {
  public:
    enum Type {
      DRAIN_QUEUE_COMMAND = -1,
      USE_CRS_COMMAND = 0,
      CLASS_LOAD,
      GCLOG,
      TO_JAVA_CALL,
      FIRST_CALL
    };

    CRSEvent *next;
    Type type;

    CRSEvent(Type type): type(type) {}

    virtual void process(TRAPS) = 0;
    virtual ~CRSEvent() {}
} *event_queue_head = NULL, **event_queue_tail = &event_queue_head;

class CRSToJavaCallEvent: public CRSEvent {
  char *name;
  uintx name_length;

public:
  static bool _should_notify;
  static char _callback[64];
  static bool _has_callback;

  friend class CRSCommandListenerThread;

  static void set_callback(const char* name) { _has_callback = name != NULL; if (_has_callback) strncpy(_callback, name, sizeof(_callback) - 1); }
  static bool has_callback() { return _has_callback; }

public:
  static void set_should_notify(bool enable) { _should_notify = enable; }
  static bool should_notify() { return _should_notify; }

  CRSToJavaCallEvent(Symbol *holder_symbol, Symbol *method_symbol)
  : CRSEvent(TO_JAVA_CALL) {
    uintx holder_name_length = holder_symbol->utf8_length();
    uintx method_name_length = method_symbol->utf8_length();
    // TODO consider leasing NativeMemory buffers for event queue
    name = NEW_C_HEAP_ARRAY(char, holder_name_length+1+method_name_length+1, mtTracing);
    holder_symbol->as_C_string(name, holder_name_length+1);
    name[holder_name_length] = '.';
    method_symbol->as_C_string(&name[holder_name_length+1], method_name_length+1);
  }

  virtual void process(TRAPS) {
    // some notifications might be pending in the queue when event is disabled
    if (!should_notify() || !has_callback())
      return;

    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    JavaValue res(T_VOID);
    Handle agentArgs = java_lang_String::create_from_str(name, CHECK);

    instanceKlassHandle ikh(THREAD, ConnectedRuntime::_callback_listener);
    JavaCalls::call_static(&res,
                           ikh,
                           SymbolTable::lookup(_callback, (int)strlen(_callback), THREAD),
                           vmSymbols::string_void_signature(),
                           agentArgs,
                           THREAD);
DEBUG_EVENT(CRSToJavaCallEvent);
    if (HAS_PENDING_EXCEPTION) {
#ifdef ASSERT
      tty->print_cr("CRSToJavaCallEvent: notification failed");
      java_lang_Throwable::print(PENDING_EXCEPTION, tty);
      tty->cr();
#endif // ASSERT
      CLEAR_PENDING_EXCEPTION;
    }
  }

  virtual ~CRSToJavaCallEvent() {
    FREE_C_HEAP_ARRAY(char, name, mtTracing);
  }
};
bool CRSToJavaCallEvent::_should_notify = true;
char CRSToJavaCallEvent::_callback[64] = {0};
bool CRSToJavaCallEvent::_has_callback = false;

class CrsMessage {
private:
  CrsNotificationType _type;
  u2 _size;

#if DEBUG
  static uintx _message_count[CRS_MESSAGE_TYPE_COUNT];
  static uintx _message_all_sizes[CRS_MESSAGE_TYPE_COUNT];
#endif

protected:
  CrsMessage(CrsNotificationType type, u4 size) : _type(type), _size((u2)size) {
    assert(size < 1u<<16, "sanity");
#if DEBUG
    _message_count[type]++;
    _message_all_sizes[type] += size;
#endif
  }

  static Klass* agent_klass() { return ConnectedRuntime::_callback_listener; }
public:
  u2 size() const { return _size; }
  CrsNotificationType type() const { return _type; }
  void process(TLB *tlb, TRAPS) const;
  void print_on(outputStream *s) const;

#if DEBUG
  static void print_statistics();
#endif
};

class CrsClassLoadMessage : public CrsMessage {
  friend class CRSCommandListenerThread;

  crs_traceid _loaderId;
  crs_traceid _klass_id;

  struct {
    int has_hash: 1;
    int has_original_hash: 1; // note that for not transformed classes this is not set
    int has_source: 1;
    int has_same_source: 1;
  } _flags;

  u1 _original_hash[DL_SHA256]; // only used when class is transformed
  u1 _hash[DL_SHA256];
  int _klass_name_length;
  char _data[]; // klass name, source

  static bool _should_notify;
  static char _callback[64];
  static bool _has_callback;

  CrsClassLoadMessage(uintx size, instanceKlassHandle ikh, bool is_transformed, u1 const *original_hash, u1 const *hash, const char *source, CrsClassLoadMessage *reference, int klass_name_length) :
  CrsMessage(CRS_MESSAGE_CLASS_LOAD, size), _flags(), _klass_name_length(klass_name_length) {

    _loaderId = ikh()->class_loader_data()->crs_trace_id();
    _klass_id = ikh()->crs_trace_id();
    assert(_klass_id, "must be known named klass");
    if (is_transformed && original_hash) {
      _flags.has_original_hash = 1;
      memcpy(this->_original_hash, original_hash, sizeof (this->_original_hash));
    }
    if (hash) {
      _flags.has_hash = 1;
      memcpy(this->_hash, hash, sizeof (this->_hash));
    }

    int klass_name_size = klass_name_length + 1;

    ikh()->name()->as_C_string(&_data[0], klass_name_size);

    if (reference != NULL) {
      _flags.has_same_source = 1;
      assert(offset_of(CrsClassLoadMessage, _data) + klass_name_size <= size && size <= sizeof(CrsClassLoadMessage) + klass_name_size, "sanity");
    } else if (source != NULL) {
      _flags.has_source = 1;
      assert(((char*)this) + size >= &_data[klass_name_size] + strlen(source) + 1, "sanity");
      strcpy(&_data[klass_name_size], source);
    } else {
      assert(offset_of(CrsClassLoadMessage, _data) <= size && size <= sizeof(CrsClassLoadMessage) + klass_name_size, "sanity");
    }
  }

  const char* klass_name() const {
    return &_data[0];
  }

  const char* source() const {
    return &_data[_klass_name_length + 1];
  }

  static void set_callback(const char* name) { _has_callback = name != NULL; if (_has_callback) strncpy(_callback, name, sizeof(_callback) - 1); }
  static bool has_callback() { return _has_callback; }

public:

static void post(NativeMemory *memory, instanceKlassHandle ikh, bool is_transformed, u1 const *original_hash, u1 const *hash, const char *source, Thread *thread) {
    CrsClassLoadMessage *previous_reference =
            (CrsClassLoadMessage*) memory->reference_message(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD, thread);
    // sanity check reference message. it might have been set as reference by occasion,
    // because of buffer overflow but really it has no source
    if (previous_reference != NULL && !previous_reference->_flags.has_source) {
      previous_reference = NULL;
    }
    // normalize "" to NULL, the encoding assumes string is non-empty
    if (source != NULL && source[0] == '\0') {
      source = NULL;
    }
    bool is_new_reference = (source && previous_reference) ?
            strcmp(previous_reference->source(), source) :
            (source && !previous_reference);

    int name_length = ikh()->name()->utf8_length();
    const jlong size_no_ref = offset_of(CrsClassLoadMessage, _data) + name_length + 1;
    const jlong size_with_ref = size_no_ref + (source ? strlen(source) + 1 : 0);
    const jlong size = is_new_reference ? size_with_ref : size_no_ref;

    void *msg = memory->alloc(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD, &is_new_reference, size, size_with_ref, thread);

    if (msg != NULL) {
      new (msg) CrsClassLoadMessage(
              is_new_reference ? size_with_ref : size,
              ikh, is_transformed, original_hash, hash, source,
              is_new_reference ? NULL : previous_reference,
              name_length);
    }
  }

  void process(TLB *tlb, TRAPS) const;
  void print_on(outputStream *s) const;

  static void set_should_notify(bool enable) { _should_notify = enable; }
  static bool should_notify() { return _should_notify; }
};

class CrsFirstCallMessage : public CrsMessage {
  crs_traceid _holder_id;
  char _method_name_sig[];

  static bool _should_notify;
  static char _callback[64];
  static bool _has_callback;

  friend class CRSCommandListenerThread;

  CrsFirstCallMessage(int size, Method* m, int method_name_length, int method_sig_length): CrsMessage(CRS_MESSAGE_FIRST_CALL, size) {
    _holder_id = m->method_holder()->crs_trace_id();
    m->name()->as_C_string(&_method_name_sig[0], method_name_length + 1);
    m->signature()->as_C_string(&_method_name_sig[method_name_length], method_sig_length + 1);
  }

  static void set_callback(const char* name) { _has_callback = name != NULL; if (_has_callback) strncpy(_callback, name, sizeof(_callback) - 1); }
  static bool has_callback() { return _has_callback; }

public:
  static void post(NativeMemory *memory, Method *method, Thread *thread) {
    int method_name_length = method->name()->utf8_length();
    int method_sig_length = method->signature()->utf8_length();
    int size = method_name_length + method_sig_length + 1 + sizeof (CrsFirstCallMessage);
    void *msg = memory->alloc(size, thread);
    if (msg != NULL) {
      new (msg) CrsFirstCallMessage(size, method, method_name_length, method_sig_length);
    }
  }

  static void set_should_notify(bool enable) { _should_notify = enable; }
  static bool should_notify() { return _should_notify; }

  void process(TRAPS) const;
  void print_on(outputStream *s) const;
};

class MessageClosure : public TLBClosure {
public:
  virtual void tlb_do(TLB* tlb);
protected:
  virtual void message_do(TLB *tlb, CrsMessage *message) = 0;
};

class TLBFlushClosure: public MessageClosure {
  Thread *_thread;
public:
  TLBFlushClosure(Thread *thread): _thread(thread) {}
  virtual void tlb_do(TLB *tlb);
  virtual void message_do(TLB *tlb, CrsMessage *msg);
};

void NativeMemory::flush(TRAPS) {
  const uintx next_target = (_previous_usage + _tlb_manager.bytes_used()) / 2;
  _previous_usage = _tlb_manager.bytes_used();

  if (DEBUG) tty->print_cr("CRS native buffers flush. %" PRIuPTR " bytes used. reserve %" PRIuPTR "->%" PRIuPTR,
      _previous_usage, _tlb_manager.bytes_committed(), next_target);
  uintx before = _tlb_manager.bytes_used();
  TLBFlushClosure f(THREAD);
  _tlb_manager.flush_buffers(&f, next_target);
  if (_overflow) {
    tty->print_cr("CRS native buffer overflow, data is lost [%" PRIuPTR "->%" PRIuPTR "]",
            before, _tlb_manager.bytes_used());
    _overflow = false;
  }
}

class TLBReleaseClosure: public TLBClosure {
public:
  virtual void tlb_do(TLB *tlb);
};

void NativeMemory::release_buffers() {
  TLBReleaseClosure f;
  _tlb_manager.leased_buffers_do(&f);
}

#if DEBUG
uintx CrsMessage::_message_count[CRS_MESSAGE_TYPE_COUNT];
uintx CrsMessage::_message_all_sizes[CRS_MESSAGE_TYPE_COUNT];
#endif

void CrsMessage::print_on(outputStream *s) const {
  ResourceMark rm;

  switch (type()) {
    case CRS_MESSAGE_CLASS_LOAD:
      static_cast<CrsClassLoadMessage const*>(this)->print_on(tty);
      break;
    case CRS_MESSAGE_FIRST_CALL:
      static_cast<CrsClassLoadMessage const*>(this)->print_on(tty);
      break;
    default:
      ShouldNotReachHere();
  }
}

void CrsMessage::process(TLB *tlb, TRAPS) const {
  ResourceMark rm;

  switch (type()) {
    case CRS_MESSAGE_CLASS_LOAD:
      static_cast<CrsClassLoadMessage const *>(this)->process(tlb, THREAD);
      break;
    case CRS_MESSAGE_FIRST_CALL:
      static_cast<CrsFirstCallMessage const *>(this)->process(THREAD);
      break;
    default:
      if (DEBUG) tty->print_cr("unexpected message type %d", type());
      ShouldNotReachHere();
  }
}

#if DEBUG
const char * const crs_message_type_name[] = {
  "class load",
  "first call",
};

void CrsMessage::print_statistics() {
  tty->print_cr("CRS message statistics");
  for (int i = 0; i < CRS_MESSAGE_TYPE_COUNT; i++)
    if (_message_count[i] > 0)
      tty->print_cr(" type %s count " PRIuPTR " total size " PRIuPTR, crs_message_type_name[i], _message_count[i], _message_all_sizes[i]);
}
#endif

bool CrsClassLoadMessage::_should_notify = true;
char CrsClassLoadMessage::_callback[64] = {0};
bool CrsClassLoadMessage::_has_callback = false;

void CrsClassLoadMessage::print_on(outputStream* s) const {
  s->print_cr(" class load: %s", klass_name());
}

void CrsClassLoadMessage::process(TLB *tlb, TRAPS) const {
    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    Handle name_handle = java_lang_String::create_from_str(klass_name(), CHECK);

    JavaValue res(T_VOID);
    JavaCallArguments agentArgs;
    Handle source_handle;
    if (_flags.has_source) {
      source_handle = java_lang_String::create_from_str(source(), CHECK);
      tlb->set_reference_message(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD, (u1*)this);
    } else if (_flags.has_same_source) {
      CrsClassLoadMessage const *ref =
          (CrsClassLoadMessage const *)(tlb->reference_message(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD));
      assert(ref && ref->_flags.has_source, "sanity");
      source_handle = java_lang_String::create_from_str(ref->source(), CHECK);
      assert(size() <= sizeof(CrsClassLoadMessage) + _klass_name_length + 1, "sanity");
    }

    if (!has_callback()) {
      return;
    }

    typeArrayOop original_hash_oop = NULL;
    if (_flags.has_original_hash) {
      original_hash_oop = oopFactory::new_byteArray(sizeof(_original_hash), CHECK);
      memcpy(original_hash_oop->byte_at_addr(0), _original_hash, sizeof(_original_hash));
    }
    typeArrayHandle original_hash_handle(THREAD, original_hash_oop);
    typeArrayOop hash_oop = NULL;
    if (_flags.has_hash) {
      hash_oop = oopFactory::new_byteArray(sizeof(_hash), CHECK);
      memcpy(hash_oop->byte_at_addr(0), _hash, sizeof(_hash));
    }
    typeArrayHandle hash_handle(THREAD, hash_oop);

    instanceKlassHandle ikh(THREAD, agent_klass());
    agentArgs.push_oop(name_handle);
    agentArgs.push_oop(original_hash_handle);
    agentArgs.push_oop(hash_handle);
    agentArgs.push_int(_klass_id);
    agentArgs.push_int(_loaderId);
    agentArgs.push_oop(source_handle);
    JavaCalls::call_static(&res,
                           ikh,
                           SymbolTable::lookup(_callback, (int)strlen(_callback), THREAD),
                           vmSymbols::notifyClassLoad_signature(),
                           &agentArgs,
                           THREAD
      );
DEBUG_EVENT(CrsClassLoadMessage);
    if (HAS_PENDING_EXCEPTION) {
#ifdef ASSERT
      tty->print_cr("CrsClassLoadMessage: notification failed");
      java_lang_Throwable::print(PENDING_EXCEPTION, tty);
      tty->cr();
#endif // ASSERT
      CLEAR_PENDING_EXCEPTION;
    }
}

bool CrsFirstCallMessage::_should_notify = true;
bool CrsFirstCallMessage::_has_callback = false;
char CrsFirstCallMessage::_callback[64] = {0};

void CrsFirstCallMessage::process(TRAPS) const {
    if (!has_callback()) {
      return;
    }

    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    Handle methodName = java_lang_String::create_from_str(_method_name_sig, CHECK);

    JavaCallArguments agentArgs;

    agentArgs.push_int(_holder_id);
    agentArgs.push_oop(methodName);

    instanceKlassHandle ikh(THREAD, agent_klass());
    JavaValue res(T_VOID);
    JavaCalls::call_static(&res,
                           ikh,
                           SymbolTable::lookup(_callback, (int)strlen(_callback), THREAD),
                           vmSymbols::notifyFirstCall_signature(),
                           &agentArgs,
                           THREAD);
DEBUG_EVENT(CrsFirstCallMessage);
    if (HAS_PENDING_EXCEPTION) {
#ifdef ASSERT
      tty->print_cr("CrsFirstCallMessage: notification failed");
      java_lang_Throwable::print(PENDING_EXCEPTION, tty);
      tty->cr();
#endif // ASSERT
      CLEAR_PENDING_EXCEPTION;
    }
}

void CrsFirstCallMessage::print_on(outputStream* s) const {
  s->print_cr(" first call: %s", _method_name_sig);
}

void MessageClosure::tlb_do(TLB* tlb) {
  u1 *p = tlb->base();
  u1 *f = p + tlb->pos();
  while (p < f) {
    CrsMessage *msg = (CrsMessage*)p;
    p += align_up(msg->size(), TLBManager::align);
    message_do(tlb, msg);
  }
}

void TLBFlushClosure::tlb_do(TLB* tlb) {
  if(/*DEBUG*/0) tty->print_cr("flush buffer %p, data size %" PRIuPTR, tlb, tlb->pos());
  MessageClosure::tlb_do(tlb);
}

void TLBFlushClosure::message_do(TLB *tlb, CrsMessage* msg) {
  msg->process(tlb, _thread);
}

void TLBReleaseClosure::tlb_do(TLB* tlb) {
  Thread *owner = tlb->owner();
  assert(SafepointSynchronize::is_at_safepoint() || Thread::current() == owner,
          "cannot flush active buffer asynchronously");
  // since we are on the same thread or in safepoint no concurrent modifications
  // to buffer can occur
  if (owner) {
    tlb->release();
    owner->crs_thread_locals()->set_buffer(NULL);
  }
}

static bool _event_gclog = false;
static NativeMemory *memory = NULL;

class VM_CRSOperation: public VM_Operation {
  bool (*_op_pre)();
  void (*_op_do)();
  bool _and_stop;

public:
  VM_CRSOperation(bool (*op_pre)(), void (*op_do)(), bool and_stop): VM_Operation(),
          _op_pre(op_pre), _op_do(op_do), _and_stop(and_stop) {}

  virtual VMOp_Type type() const { return VMOp_CRSOperation; }

  virtual bool doit_prologue() {
    return !_op_pre || (*_op_pre)();
  }

  virtual void doit() {
    assert(SafepointSynchronize::is_at_safepoint(), "must be");
    (*_op_do)();
    if (_and_stop) {
      CrsFirstCallMessage::set_should_notify(false);
      CrsClassLoadMessage::set_should_notify(false);
    }
  }
};

void ConnectedRuntime::init() {
  parse_options();

  if (ConnectedRuntime::is_CRS_in_use()) {
    if (_log_level == CRS_LOG_LEVEL_NOT_SET)
      _log_level = CRS_LOG_LEVEL_ERROR;

    memory = new NativeMemory(AzCRSNativeMemoryAreaSize);

    bool com_azul_tooling_events_set = false;
    const static char *default_event_list = "JarLoad";
    const static char *tooling_name = "com.azul.tooling.events";

    for (SystemProperty* p = Arguments::system_properties(); p != NULL; p = p->next()) {
      if (strcmp(tooling_name, p->key()) == 0) {
        // don't use p->append_value(default_event_list) since it uses 'os::path_separator()' which is ';' instead of ',' expecting by com.azul.tooling
        char buf[1024];
        ssize_t n = jio_snprintf(buf, sizeof(buf), "%s,%s", p->value(), default_event_list);
        if (n >= 0 && n <= (ssize_t)sizeof(buf)) {
          com_azul_tooling_events_set = true;
          p->set_value(buf);
        } else {
          log_warning("arguments for %s are too long and will be truncated.", tooling_name);
        }
        break;
      }
    }

    if (!com_azul_tooling_events_set) {
      Arguments::PropertyList_add(new SystemProperty("com.azul.tooling.events", default_event_list, true));
    }

    if (Arguments::get_property("com.azul.crs.jarload.sendCentralDirectoryHashOnJarLoad") == NULL) {
      Arguments::PropertyList_add(new SystemProperty("com.azul.crs.jarload.sendCentralDirectoryHashOnJarLoad", "true", true));
    }
  }
}

/*
 * Compares two strings, value1 must be '\0'-terminated, length of value2 is supplied in value2_len argument
 */
static bool strnequals(const char *value1, const char *value2, size_t value2_len) {
  return !strncmp(value1, value2, value2_len) && !value1[value2_len];
}

void ConnectedRuntime::parse_log_level(LogLevel *var, const char *value, size_t value_len) {
  static const char * const values[] = { "trace", "debug", "info", "warning", "error", "off" };
  for (size_t i=0; i<sizeof(values)/sizeof(values[0]); i++) {
    if (strnequals(values[i], value, value_len)) {
       *var = static_cast<LogLevel>(i);
       break;
    }
  }
}

void ConnectedRuntime::parse_arguments(const char *arguments, bool needs_unlock) {
  static const char * const options[] = { "log", "log+vm", ENABLE_CRS_ARGUMENT, UNLOCK_CRS_ARGUMENT, DELAY_INITIATION, NOTIFY_FIRST_CALL };

  LogLevel global_log_level = CRS_LOG_LEVEL_NOT_SET;
  LogLevel vm_log_level = CRS_LOG_LEVEL_NOT_SET;

  bool enable_crs = false; // true if enable=true is set
  bool disable_crs = false; // true if enable=false is set
  bool unlock_is_set = false; // true if UnlockExperimentalCRS is set
  long delayInitiation = _delayInitiation;

  const char *comma;
  const char *equals;
  while (true) {
    comma = strchr(arguments, ',');
    equals = strchr(arguments, '=');
    if (!comma)
      comma = arguments + strlen(arguments);
    if (equals && equals < comma) {
      for (size_t i=0; i<sizeof(options)/sizeof(options[0]); i++)
        if (!strncmp(arguments, options[i], equals-arguments)) {
          const char *value = equals+1;
          const size_t value_len = comma-value;
          switch (i) {
            case 0: // log
              parse_log_level(&global_log_level, value, value_len);
              break;
            case 1: // log+vm
              parse_log_level(&vm_log_level, value, value_len);
              break;
            case 2: // enable
              if (strnequals(ENABLE_CRS_TRUE, value, value_len)) {
                enable_crs = true;
                disable_crs = false;
              } else if (strnequals(ENABLE_CRS_FALSE, value, value_len)) {
                enable_crs = false;
                disable_crs = true;
              }
              break;
            case 4: // delayInitiation
              delayInitiation = strtol(value, NULL, 10);
              break;
            case 5: // notifyFirstCall
              if (strnequals(ENABLE_CRS_TRUE, value, value_len)) {
                _should_notify_first_call = true;
              }
              break;
          }
        }
    } else {
      if (strnequals(ENABLE_CRS_ARGUMENT, arguments, comma-arguments)) {
          enable_crs = true;
      } else if (strnequals(UNLOCK_CRS_ARGUMENT, arguments, comma-arguments)) {
          unlock_is_set = true;
          log_error("UnlockExperimentalCRS is deprecated");
      } else if (strnequals(NOTIFY_FIRST_CALL, arguments, comma-arguments)) {
          _should_notify_first_call = true;
      }
    }

    if (!*comma)
      break;

    arguments = comma+1;
  }

  if (_crs_mode == CRS_MODE_ON && disable_crs == true) {
    fatal_or_log(log_warning, "There is conflict in flags: -XX:AzCRSMode=on and enable=false at the same time.");
  }

  if (FLAG_IS_DEFAULT(AzCRSMode) && (enable_crs || disable_crs) && (!needs_unlock || unlock_is_set)) {
    if (enable_crs) {
      _crs_mode = CRS_MODE_AUTO;
      FLAG_SET_DEFAULT(AzCRSMode, "auto");
    } else {
      _crs_mode = CRS_MODE_OFF;
      FLAG_SET_DEFAULT(AzCRSMode, "off");
    }
  }

  if ((delayInitiation != _delayInitiation) &&
       (delayInitiation >= 0) &&
       (delayInitiation < INT_MAX)) {
    _delayInitiation = (int) delayInitiation;
  }

  if (vm_log_level != CRS_LOG_LEVEL_NOT_SET)
    _log_level = vm_log_level;
  else if (global_log_level != CRS_LOG_LEVEL_NOT_SET)
    _log_level = global_log_level;
}

void ConnectedRuntime::parse_options() {

  if (!strcmp(CRS_MODE_STR_ON, AzCRSMode)) {
    _crs_mode = CRS_MODE_ON;
  } else
  if (!strcmp(CRS_MODE_STR_OFF, AzCRSMode)) {
    _crs_mode = CRS_MODE_OFF;
  } else
  if (!strcmp(CRS_MODE_STR_AUTO, AzCRSMode)) {
    _crs_mode = CRS_MODE_AUTO;
  } else {
    fatal_or_log(log_error, "Unexpected value of -XX:AzCRSMode='%s' flag. Expecting one of on/off/auto", AzCRSMode);
  }

  const size_t ENV_ARGS_LENGTH = 4096;
  char env_args[ENV_ARGS_LENGTH];
  if (os::getenv(ARGS_ENV_VAR_NAME, env_args, sizeof(env_args)-1))
    parse_arguments(env_args, true);
  if (AzCRSArguments)
    parse_arguments(AzCRSArguments, false);
}

static Handle get_crs_agent_class(Handle url_string, TRAPS) {
  // create URLClassLoader with only crs-agent.jar on the class path
  // first create respective URL instance
  instanceKlassHandle url_klass(THREAD, SystemDictionary::URL_klass());
  url_klass->initialize(THREAD);
  Handle url_instance = url_klass->allocate_instance(CHECK_NH);
  JavaValue void_result(T_VOID);
  JavaCallArguments url_init_args;
  url_init_args.push_oop(url_instance);
  url_init_args.push_oop(url_string);
  JavaCalls::call_special(&void_result,
          url_klass,
          vmSymbols::object_initializer_name(),
          vmSymbols::string_void_signature(),
          &url_init_args,
          CHECK_NH);

  instanceKlassHandle url_class_loader_klass(THREAD, SystemDictionary::URLClassLoader_klass());
  url_class_loader_klass->initialize(THREAD);
  Handle class_loader_instance = url_class_loader_klass->allocate_instance_handle(CHECK_NH);
  objArrayOop url_array = oopFactory::new_objArray(SystemDictionary::URL_klass(), 1, CHECK_NH);
  url_array->obj_at_put(0, url_instance());
  JavaCallArguments args;
  args.push_oop(class_loader_instance);
  args.push_oop(Handle(url_array));
  args.push_oop(Handle());
  JavaCalls::call_special(&void_result,
          url_class_loader_klass,
          vmSymbols::object_initializer_name(),
          vmSymbols::url_class_loader_initializer_signature(),
          &args,
          CHECK_NH);

  // and load CRS agent class with created class loader
  Handle crs_agent_class_name_handle = java_lang_String::create_from_str(CRS_AGENT_CLASS_NAME, CHECK_NH);
  JavaValue obj_result(T_OBJECT);
  JavaCalls::call_virtual(&obj_result,
          class_loader_instance,
          url_class_loader_klass,
          vmSymbols::loadClass_name(),
          vmSymbols::string_class_signature(),
          crs_agent_class_name_handle,
          CHECK_NH);

  return Handle((oop)obj_result.get_jobject());
}

void initializeAndStart(const char* thread_name, ThreadPriority priority, JavaThreadCreateFunction thread_create) {
EXCEPTION_MARK;
    //XXX: something in between CompileBroker::make_compiler_thread and Zing's JavaSystemThreadInitializationSupport::initializeAndStart
    Klass* k =
      SystemDictionary::resolve_or_fail(vmSymbols::java_lang_Thread(),
                                        true, CHECK);
    instanceKlassHandle klass (THREAD, k);
    instanceHandle thread_oop = klass->allocate_instance_handle(CHECK);
    Handle string = java_lang_String::create_from_str(thread_name, CHECK);

    // Initialize thread_oop to put it into the system threadGroup
    Handle thread_group (THREAD,  Universe::system_thread_group());
    JavaValue result(T_VOID);
    JavaCalls::call_special(&result, thread_oop,
                         klass,
                         vmSymbols::object_initializer_name(),
                         vmSymbols::threadgroup_string_void_signature(),
                         thread_group,
                         string,
                         CHECK);

    {
      MutexLocker mu(Threads_lock, THREAD);
      JavaThread *thread = thread_create();

      // At this point it may be possible that no osthread was created for the
      // JavaThread due to lack of memory. We would have to throw an exception
      // in that case. However, since this must work and we do not allow
      // exceptions anyway, check and abort if this fails.

      if (thread == NULL || thread->osthread() == NULL){
         ConnectedRuntime::disable("unable to create new native thread", true);
         delete thread;
         return;
      }

      java_lang_Thread::set_thread(thread_oop(), thread);
      java_lang_Thread::set_priority(thread_oop(), priority);
      java_lang_Thread::set_daemon(thread_oop());

      thread->set_threadObj(thread_oop());

      Threads::add(thread);
      Thread::start(thread);
    }

    // Let go of Threads_lock before yielding
    os::yield(); // make sure that the listener thread is started early (especially helpful on SOLARIS)
}

void ConnectedRuntime::startAgent(TRAPS) {
    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    // Engage the CRS daemons
    const char *home = Arguments::get_java_home();
    const size_t home_len = strlen(home);
    const size_t crs_jar_url_len = sizeof(FILE_URL_PREFIX)+home_len+sizeof(CRS_AGENT_JAR_PATH)-1;
    char *crs_jar_url = NEW_C_HEAP_ARRAY_RETURN_NULL(char, crs_jar_url_len, mtTracing);
    Handle crs_jar_url_handle;
    if (crs_jar_url) {
      strncpy(crs_jar_url, FILE_URL_PREFIX, crs_jar_url_len);
      strncpy(crs_jar_url+sizeof(FILE_URL_PREFIX)-1, home, crs_jar_url_len-sizeof(FILE_URL_PREFIX)+1);
      strncpy(crs_jar_url+sizeof(FILE_URL_PREFIX)-1+home_len, CRS_AGENT_JAR_PATH, crs_jar_url_len-sizeof(FILE_URL_PREFIX)+1-home_len);
      crs_jar_url_handle = java_lang_String::create_from_str(crs_jar_url, THREAD);
    }

    Handle agent_class_handle;
    if (crs_jar_url_handle.not_null())
      agent_class_handle = get_crs_agent_class(crs_jar_url_handle, THREAD);

    jobject agent_class_jni_handle;
    if (agent_class_handle.not_null()) {

      // anchor the agent class so it's not taken by GC
      agent_class_jni_handle = JNIHandles::make_global(agent_class_handle);
      _agent_klass = java_lang_Class::as_Klass(agent_class_handle());
      instanceKlassHandle agent_klass_handle(THREAD, _agent_klass);

      char args[1024] = {0};
      strncat(args, agentAuthArgs, sizeof(args) - 1);
      if (_crs_mode == CRS_MODE_ON) {
        const char *cc = "mode=on,";
        strncat(args, cc, sizeof(args) - strlen(args) - 1);
      } else if (_crs_mode == CRS_MODE_AUTO) {
        const char *cc = "mode=auto,";
        strncat(args, cc, sizeof(args) - strlen(args) - 1);
      } else if (_crs_mode == CRS_MODE_OFF) {
        fatal_or_log(log_error, "Trying to start CRS agent when AzCRSMode=off");
      }
      if (AzCRSFailJVMOnError) {
        const char *cc = "failJVMOnError,";
        strncat(args, cc, sizeof(args) - strlen(args) - 1);
      }
      if (AzCRSArguments != NULL) {
        if (strlen(AzCRSArguments) >= sizeof(args) - strlen(args)) {
          fatal_or_log(log_error, "AzCRSArguments are too long and will be truncated.");
        }
        strncat(args, AzCRSArguments, sizeof(args) - strlen(args) - 1);
      }

      JavaValue void_result(T_VOID);

      Handle agentArgs0;
      agentArgs0 = java_lang_String::create_from_str(args, THREAD);

      JavaCallArguments agentArgs;

      agentArgs.push_oop(agentArgs0);
      agentArgs.push_oop(Handle());

      if (!HAS_PENDING_EXCEPTION) {
        JavaCalls::call_static(&void_result,
                               agent_klass_handle,
                               vmSymbols::javaAgent_premain_name(),
                               vmSymbols::javaAgent_premain_signature(),
                               &agentArgs,
                               THREAD);
      }
    }
    if (!_agent_klass || HAS_PENDING_EXCEPTION) {
      if (HAS_PENDING_EXCEPTION && _log_level == CRS_LOG_LEVEL_NOT_SET)
        _log_level = CRS_LOG_LEVEL_ERROR;

      disable("Cannot start Connected Runtime Services", true);
      if (HAS_PENDING_EXCEPTION) {
        if (_log_level == CRS_LOG_LEVEL_TRACE) {
          java_lang_Throwable::print(PENDING_EXCEPTION, tty);
          tty->cr();
        }
        CLEAR_PENDING_EXCEPTION;
      }
      return;
    }

    // XXX membar
    _is_init = true;
    notify_java(THREAD);
}


void ConnectedRuntime::engage(TRAPS) {
  if (ConnectedRuntime::is_CRS_in_use()) {
    CRSCommandListenerThread::start();
    _crs_engaged = true;
    if (_delayInitiation > 0)
      CRSAgentInitThread::start();
    else
      startAgent(THREAD);
  }
}

static void release_memory_do() {
  for (JavaThread* tp = Threads::first(); tp != NULL; tp = tp->next()) {
    tp->crs_thread_locals()->set_buffer(NULL);
  }

  delete memory;
  memory = NULL;
}

void ConnectedRuntime::disable(const char* msg, bool need_safepoint) {
  if (msg && _log_level <= CRS_LOG_LEVEL_ERROR) {
    tty->print_cr("CRS agent initialization failure: %s\n"
          "Disabling Connected Runtime services.", msg);
  }
  _crs_mode = CRS_MODE_OFF;

  if (memory) {
    if (need_safepoint) {
      VM_CRSOperation op(NULL, &release_memory_do, true);
      VMThread::execute(&op);
    } else {
      delete memory;
      memory = NULL;
    }
  }
}

void ConnectedRuntime::notify_class_load(instanceKlassHandle ikh, bool is_transformed,
        u1 const *original_hash, u1 const *hash, uintx hash_length, char const *source, TRAPS) {
  if (ConnectedRuntime::is_CRS_in_use() && CrsClassLoadMessage::should_notify()) {
    assert(hash_length == DL_SHA256, "sanity");
    CrsClassLoadMessage::post(memory, ikh, is_transformed, original_hash, hash, source, THREAD);
  }
}

void ConnectedRuntime::notify_tojava_call(methodHandle* m) {
  // ignore VM startup
  if (!ConnectedRuntime::is_CRS_in_use() || !_crs_engaged || !CRSToJavaCallEvent::should_notify())
    return;

  methodHandle method = *m;
  // skip initializers
  if (method->is_static_initializer() || method->is_initializer())
    return;
  InstanceKlass *holder = method->method_holder();
  // ignore own calls
  if (holder == _agent_klass)
    return;

  // calls from native into Java must be processed by CRS agent rather quickly
  // at the same time synchronized processing does not impose noticeable overhead compared to existing
  // so we use event queue, drained by service thread for this purpose
  // TODO consider using NativeMemory buffers instead of C_HEAP
  schedule(new(ResourceObj::C_HEAP, mtTracing) CRSToJavaCallEvent(holder->name(), method->name()), CRSToJavaCallEvent::has_callback());
}

void ConnectedRuntime::notify_first_call(JavaThread *thread, Method *method) {
  if (ConnectedRuntime::is_CRS_in_use() && CrsFirstCallMessage::should_notify()) {
    if (DEBUG) tty->print_cr("method call %p holder %p %d", method, method->method_holder(), (int)method->method_holder()->crs_trace_id());
    CrsFirstCallMessage::post(memory, method, thread);
  }
}

void ConnectedRuntime::notify_thread_exit(Thread* thread) {
  memory->release_thread_buffer(thread);
}

void ConnectedRuntime::schedule(CRSEvent *event, bool has_callback) {
  MutexLockerEx ml(Service_lock, Mutex::_no_safepoint_check_flag);

  _should_notify = true;

  event->next = NULL;
  *event_queue_tail = event;
  event_queue_tail = &(event->next);

  if (_is_init && has_callback)
    Service_lock->notify_all();
}

bool ConnectedRuntime::should_notify_java() {
  return _should_notify;
}

bool ConnectedRuntime::should_notify_first_call() {
  return _should_notify_first_call && is_CRS_in_use();
}

void ConnectedRuntime::flush_events(bool do_process, TRAPS) {
  bool more = true;

  while (more) {
    CRSEvent *e;
    {
      MutexLockerEx ml(Service_lock, Mutex::_no_safepoint_check_flag);

      _should_notify = false;

      e = event_queue_head;
      if (event_queue_tail == &event_queue_head) {
        break;
      } else if (event_queue_tail == &(event_queue_head->next)) {
        event_queue_tail = &event_queue_head;
        more = false;
      } else {
        event_queue_head = e->next;
      }
    }

    if (do_process)
      e->process(THREAD);

    delete e;
  }
}

void ConnectedRuntime::notify_java(TRAPS) {
  if (!ConnectedRuntime::_is_init) // not yet init, need to wait
    return;

  flush_events(true, THREAD);
}

void ConnectedRuntime::clear_event_queue() {
  if (!ConnectedRuntime::_is_init) // not yet init, need to wait
    return;

  flush_events(false, NULL);
}

static bool release_buffers_pre() {
  return memory->bytes_used() > 0;
}

static void release_buffers_do() {
  memory->release_buffers();
}

void ConnectedRuntime::flush_buffers(bool force, bool and_stop, TRAPS) {
  if (!_is_init) // not yet init, need to wait
    return;

  if (and_stop) {
    CRSCommandListenerThread::stop();
  }

  if (force) {
    // force release all currently used buffers. must synchronize
    // in order to avoid inconsistent event stream at shutdown need to disable
    // all events if and_stop is set
    VM_CRSOperation release_buffers_op(&release_buffers_pre, &release_buffers_do, and_stop);
    VMThread::execute(&release_buffers_op);
  }

  memory->flush(THREAD);

#if DEBUG
  if (force)
    CrsMessage::print_statistics();
#endif
}

void ConnectedRuntime::assign_trace_id(ClassLoaderData* cld) {
  static crs_traceid cld_traceid = 0;
  if (cld->is_anonymous())
    cld->set_crs_trace_id((crs_traceid)0);
  else
    cld->set_crs_trace_id((crs_traceid)Atomic::add(1, &cld_traceid));
}

void ConnectedRuntime::assign_trace_id(InstanceKlass *ik) {
  static crs_traceid ik_traceid = 0;
  ik->set_crs_trace_id((crs_traceid)Atomic::add(1, &ik_traceid));
}

void ConnectedRuntime::mark_anonymous(InstanceKlass *ik) {
  ik->set_crs_trace_id(0);
}

const char* CRSCommandListenerThread::process_cmd(const char* cmd) {
  log_trace("CRS Listener: processing command '%s'", cmd);

  if (strncmp("disableCRS()", cmd, sizeof ("disableCRS()") - 1) == 0) {
    stop();
    ConnectedRuntime::disable(NULL, true);
    return NULL;
  }

  if (strncmp("enableEventNotifications(", cmd, sizeof ("enableEventNotifications(") - 1) == 0) {
    int event, enabled;
    int res = sscanf((cmd + sizeof ("enableEventNotifications") - 1), "(%d,%d)", &event, &enabled);
    if (res == 2) {
      switch (event) {
        case CRS_EVENT_TO_JAVA_CALL:
          CRSToJavaCallEvent::set_should_notify(enabled);
          if (!enabled) {
            ConnectedRuntime::clear_event_queue();
          }
          break;
        case CRS_MESSAGE_FIRST_CALL:
          CrsFirstCallMessage::set_should_notify(enabled);
          break;
        default:
          if (DEBUG) tty->print_cr("Unhandled case for enableEventNotifications command, eventId == %d", event);
      }
    }
    return NULL;
  }

  if (strncmp("drainQueues(", cmd, sizeof ("drainQueues(") - 1) == 0) {
    int force, stopAfterDrain;
    int res = sscanf((cmd + sizeof ("drainQueues") - 1), "(%d,%d)", &force, &stopAfterDrain);
    if (res == 2) {
      ConnectedRuntime::flush_buffers(force, stopAfterDrain, Thread::current());
    }
    return NULL;
  }

  if (strncmp("registerAgent(", cmd, sizeof ("registerAgent(") - 1) == 0) {
    char agentName[128];
    int res = sscanf((cmd + sizeof ("registerAgent") - 1), "(%127s", &agentName[0]);
    if (res == 1 && strlen(agentName) > 0 && agentName[strlen(agentName) - 1] == ')') {
      agentName[strlen(agentName) - 1 ] = '\0';
      #if 0
      // XXX: tbd: support all kind of agents
      Symbol *s = SymbolTable::lookup(&agentName[0], strlen(&agentName[0]), THREAD);
      ConnectedRuntime::_callback_listener = SystemDictionary::resolve_or_fail(s, true, CHECK_NULL);
      #else
      if (!strcmp(CRS_AGENT_CLASS_NAME, &agentName[0])) {
        ConnectedRuntime::_callback_listener = ConnectedRuntime::_agent_klass;
        log_trace("registering agent %s", &agentName[0]);
      } else {
        log_trace("requested to register unsupported agent");
      }
      #endif

    }
    return NULL;
  }

  if (strncmp("registerCallback(", cmd, sizeof ("registerCallback(") - 1) == 0) {
    int type;
    char methodName[128];
    int res = sscanf((cmd + sizeof ("registerCallback") - 1), "(%d,%127s", &type, &methodName[0]);
    if (res == 2 && strlen(methodName) > 0 && methodName[strlen(methodName) - 1] == ')') {
      // TODO - for now just take the method name and ignore the class part.
      methodName[strlen(methodName) - 1 ] = '\0';
      char* p = methodName + strlen(methodName);
      while (p >= methodName && *p != '.') {
        p--;
      }
      switch (type) {
      #define CASE(ENUM, CLASS, NOTIFY) \
        case ENUM: \
          CLASS::set_callback(p + 1); \
          if (NOTIFY && CLASS::should_notify()) \
            { MutexLockerEx ml(Service_lock, Mutex::_no_safepoint_check_flag); \
              Service_lock->notify_all(); } \
            break; \
            //end

        CASE(CRS_EVENT_TO_JAVA_CALL,       CRSToJavaCallEvent,       1)
        CASE(CRS_MESSAGE_CLASS_LOAD,       CrsClassLoadMessage,      0)
        CASE(CRS_MESSAGE_FIRST_CALL,       CrsFirstCallMessage,      0)

        default:
          log_trace("Unhandled event type!");
      #undef CASE
      }
    }
    return NULL;
  }

  log_trace("CRS Listener: command was not handled: '%s'", cmd);
  return NULL;
}

bool ConnectedRuntime::is_CRS_in_use() {
  return _crs_mode != CRS_MODE_OFF;
}

#endif // INCLUDE_CRS
