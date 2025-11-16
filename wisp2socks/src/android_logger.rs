use jni::objects::{JObject, JString, JValue};
use jni::{JNIEnv, JavaVM};
pub struct AndroidLogger<'a> {
    env: &'a JNIEnv<'a>,
    tag: JString<'a>,
}

impl<'a> AndroidLogger<'a> {
    pub fn new(env: &'a JNIEnv<'a>, tag: &str) -> Result<Self, jni::errors::Error> {
        Ok(Self { env, tag: env.new_string(tag)? })
    }

    pub fn i(&self, message: impl AsRef<str>) -> Result<(), jni::errors::Error> {
        let msg = self.env.new_string(message.as_ref())?;
        let _ = self.env.call_static_method(
            "android/util/Log",
            "i",
            "(Ljava/lang/String;Ljava/lang/String;)I",
            &[JValue::Object(JObject::from(self.tag)), JValue::Object(JObject::from(msg))],
        )?;
        Ok(())
    }
}

pub struct AndroidAsyncLogger {
    jvm: JavaVM,
    tag: String,
}

impl AndroidAsyncLogger {
    pub fn new(env: &JNIEnv, tag: &str) -> Result<Self, jni::errors::Error> {
        let jvm = env.get_java_vm()?;
        Ok(Self { jvm, tag: tag.to_string() })
    }

    pub fn i(&self, message: impl AsRef<str>) -> Result<(), jni::errors::Error> {
        let env = self.jvm.attach_current_thread()?;
        let jtag = env.new_string(self.tag.clone())?;
        let jmsg = env.new_string(message.as_ref())?;
        let _ = env.call_static_method(
            "android/util/Log",
            "i",
            "(Ljava/lang/String;Ljava/lang/String;)I",
            &[JValue::Object(JObject::from(jtag)), JValue::Object(JObject::from(jmsg))],
        )?;
        Ok(())
    }
}
