#include "src/wasm/cpp/arcs.h"
#include "src/wasm/cpp/tests/entities.h"

class HandleSyncUpdateTest : public arcs::Particle {
public:
  HandleSyncUpdateTest() {
    registerHandle("input1", input1_);
    registerHandle("input2", input2_);
    registerHandle("output", output_);
  }

  void onHandleSync(const std::string& name, bool all_synced) override {
    arcs::Data out;
    out.set_txt("sync:" + name);
    out.set_flg(all_synced);
    output_.store(&out);
  }

  void onHandleUpdate(const std::string& name) override {
    arcs::Data out;
    if (auto input = getSingleton<arcs::Data>(name)) {
      out.set_txt("update:" + name);
      out.set_num(input->get().num());
    } else {
      out.set_txt("unexpected handle name: " + name);
    }
    output_.store(&out);
  }

  arcs::Singleton<arcs::Data> input1_;
  arcs::Singleton<arcs::Data> input2_;
  arcs::Collection<arcs::Data> output_;
};

DEFINE_PARTICLE(HandleSyncUpdateTest)


class RenderTest : public arcs::Particle {
public:
  RenderTest() {
    registerHandle("flags", flags_);
  }

  std::string getTemplate(const std::string& slot_name) override {
    return "abc";
  }

  void populateModel(const std::string& slot_name, arcs::Dictionary* model) override {
    model->emplace("foo", "bar");
  }

  void onHandleUpdate(const std::string& name) override {
    const arcs::RenderFlags& flags = flags_.get();
    renderSlot("root", flags._template(), flags.model());
  }

  arcs::Singleton<arcs::RenderFlags> flags_;
};

DEFINE_PARTICLE(RenderTest)


class AutoRenderTest : public arcs::Particle {
public:
  AutoRenderTest() {
    registerHandle("data", data_);
    autoRender();
  }

  std::string getTemplate(const std::string& slot_name) override {
    const arcs::Data& data = data_.get();
    return data.has_txt() ? data.txt() : "empty";
  }

  arcs::Singleton<arcs::Data> data_;
};

DEFINE_PARTICLE(AutoRenderTest)


class EventsTest : public arcs::Particle {
public:
  EventsTest() {
    registerHandle("output", output_);
  }

  void fireEvent(const std::string& slot_name, const std::string& handler) override {
    arcs::Data out;
    out.set_txt("event:" + slot_name + ":" + handler);
    output_.set(&out);
  }

  arcs::Singleton<arcs::Data> output_;
};

DEFINE_PARTICLE(EventsTest)


class ServicesTest : public arcs::Particle {
public:
  ServicesTest() {
    registerHandle("output", output_);
  }

  void init() override {
    std::string url = resolveUrl("$resolve-me");
    arcs::ServiceResponse out;
    out.set_call("resolveUrl");
    out.set_payload(url);
    output_.store(&out);

    serviceRequest("random.next", {}, "first");
    serviceRequest("random.next", {}, "second");
    serviceRequest("clock.now", {{"timeUnit", "DAYS"}});
  }

  void serviceResponse(
      const std::string& call, const arcs::Dictionary& response, const std::string& tag) override {
    std::string payload;
    for (const auto& pair : response) {
      payload += pair.first + ":" + pair.second + ";";
    }

    arcs::ServiceResponse out;
    out.set_call(call);
    out.set_tag(tag);
    out.set_payload(payload);
    output_.store(&out);
  }

  arcs::Collection<arcs::ServiceResponse> output_;
};

DEFINE_PARTICLE(ServicesTest)


class MissingRegisterHandleTest : public arcs::Particle {
};

DEFINE_PARTICLE(MissingRegisterHandleTest)


class UnconnectedHandlesTest : public arcs::Particle {
public:
  UnconnectedHandlesTest() {
    registerHandle("data", data_);
  }

  void fireEvent(const std::string& slot_name, const std::string& handler) override {
    arcs::Data data;
    data_.set(&data);
  }

  arcs::Singleton<arcs::Data> data_;
};

DEFINE_PARTICLE(UnconnectedHandlesTest)


class InputReferenceHandlesTest : public arcs::Particle {
public:
  InputReferenceHandlesTest() {
    registerHandle("sng", sng_);
    registerHandle("col", col_);
    registerHandle("res", res_);
  }

  void onHandleSync(const std::string& name, bool all_synced) override {
    if (all_synced) {
      report("empty_before", sng_.get());
      sng_.get().dereference([this] { report("empty_after", sng_.get()); });
    }
  }

  void onHandleUpdate(const std::string& name) override {
    if (name == "sng") {
      report("s::before", sng_.get());
      sng_.get().dereference([this] { report("s::after", sng_.get()); });
    } else if (name == "col") {
      for (auto& ref : col_) {
        report("c::before", ref);
        ref.dereference([ref, this] { report("c::after", ref); });
      }
    }
  }

  void report(const std::string& label, const arcs::Ref<arcs::Data>& ref) {
    arcs::Data d;
    const std::string& id = arcs::internal::Accessor::get_id(ref);
    d.set_txt(label + " <" + id + "> " + arcs::entity_to_str(ref.entity()));
    res_.store(&d);
  }

  arcs::Singleton<arcs::Ref<arcs::Data>> sng_;
  arcs::Collection<arcs::Ref<arcs::Data>> col_;
  arcs::Collection<arcs::Data> res_;
};

DEFINE_PARTICLE(InputReferenceHandlesTest)


class OutputReferenceHandlesTest : public arcs::Particle {
public:
  OutputReferenceHandlesTest() {
    registerHandle("sng", sng_);
    registerHandle("col", col_);
  }

  void init() override {
    arcs::Ref<arcs::Data> r1;
    arcs::internal::Accessor::decode_entity(&r1, "3:idX|4:keyX|");
    sng_.set(&r1);

    arcs::Ref<arcs::Data> r2;
    arcs::internal::Accessor::decode_entity(&r2, "3:idY|4:keyY|");
    col_.store(&r1);
    col_.store(&r2);
  }

  arcs::Singleton<arcs::Ref<arcs::Data>> sng_;
  arcs::Collection<arcs::Ref<arcs::Data>> col_;
};

DEFINE_PARTICLE(OutputReferenceHandlesTest)
