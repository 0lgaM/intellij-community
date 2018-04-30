#import <Foundation/Foundation.h>
#import <Cocoa/Cocoa.h>
#import "JTypes.h"
#import "Utils.h"

@interface NSButtonJAction : NSButton
@property (nonatomic) execute jaction;
- (void)doAction;
@end

@implementation NSButtonJAction
- (void)doAction {
    if (self.jaction) {
        nstrace(@"button [%@]: doAction", self);
        (*self.jaction)();
    } else
        nstrace(@"button [%@]: empty action, nothing to execute", self);
}
@end

const int BUTTON_UPDATE_WIDTH   = 1;
const int BUTTON_UPDATE_FLAGS   = 1 << 1;
const int BUTTON_UPDATE_TEXT    = 1 << 2;
const int BUTTON_UPDATE_IMG     = 1 << 3;
const int BUTTON_UPDATE_ACTION  = 1 << 4;
const int BUTTON_UPDATE_ALL     = ~0;

const int BUTTON_FLAG_DISABLED  = 1;
const int BUTTON_FLAG_SELECTED  = 1 << 1;
const int BUTTON_FLAG_COLORED   = 1 << 2;

static void _setButtonData(NSButtonJAction *button, int updateOptions, int buttonWidth, int buttonFlags, NSString *nstext, NSImage *img, execute jaction) {
    if (updateOptions & BUTTON_UPDATE_ACTION) {
        button.jaction = jaction;
        if (jaction) {
            [button setTarget:button];
            [button setAction:@selector(doAction)];
            [button setEnabled:YES];
        } else {
            [button setTarget:nil];
            [button setAction:NULL];
            [button setEnabled:NO];
        }
    }

    if (updateOptions & BUTTON_UPDATE_TEXT) {
        [button setTitle:nstext];
        [button setImagePosition:(nstext == nil ? NSImageOnly : NSImageLeft)];
    }

    if (updateOptions & BUTTON_UPDATE_IMG)
        [button setImage:img];

    if (updateOptions & BUTTON_UPDATE_FLAGS) {
        if (buttonFlags & BUTTON_FLAG_COLORED) {
            button.bezelColor = NSColor.blueColor;
        } else if (buttonFlags & BUTTON_FLAG_SELECTED) {
            button.bezelColor = NSColor.selectedControlColor;
        } else {
            button.bezelColor = NSColor.controlColor;
        }

        const bool enabled = (buttonFlags & BUTTON_FLAG_DISABLED) == 0;
        if (enabled != button.enabled) {
            [button setEnabled:enabled];
        }
    }

    if (updateOptions & BUTTON_UPDATE_WIDTH) {
        if (buttonWidth > 0)
            [button.widthAnchor constraintEqualToConstant:buttonWidth].active = YES;
        else
            [button.widthAnchor constraintEqualToAnchor:button.widthAnchor].active = NO;
    }
}

// NOTE: called from AppKit (creation when TB becomes visible)
id createButton(
        const char *uid,
        int buttonWidth,
        int buttonFlags,
        const char *text,
        const char *raster4ByteRGBA, int w, int h,
        execute jaction
) {
    NSString *nsUid = getString(uid);
    nstrace(@"create button [%@] (thread: %@)", nsUid, [NSThread currentThread]);

    NSCustomTouchBarItem *customItemForButton = [[NSCustomTouchBarItem alloc] initWithIdentifier:nsUid]; // create non-autorelease object to be owned by java-wrapper
    NSButtonJAction *button = [[NSButtonJAction new] autorelease];
    [button setBezelStyle:NSRoundedBezelStyle];

    NSImage *img = createImgFrom4ByteRGBA((const unsigned char *) raster4ByteRGBA, w, h);
    NSString *nstext = getString(text);
    _setButtonData(button, BUTTON_UPDATE_ALL, buttonWidth, buttonFlags, nstext, img, jaction);
    customItemForButton.view = button; // NOTE: view is strong
    return customItemForButton;
}

// NOTE: called from EDT (when update UI)
void updateButton(
        id buttObj,
        int updateOptions,
        int buttonWidth,
        int buttonFlags,
        const char *text,
        const char *raster4ByteRGBA, int w, int h,
        execute jaction
) {
    NSCustomTouchBarItem *container = buttObj; // TODO: check types
    NSButtonJAction *button = (container).view; // TODO: check types

    NSAutoreleasePool *edtPool = [NSAutoreleasePool new];
    NSImage *img = createImgFrom4ByteRGBA((const unsigned char *) raster4ByteRGBA, w, h);
    NSString *nstext = getString(text);

    if ([NSThread isMainThread]) {
        nstrace(@"sync update button [%@] (main thread: %@)", container.identifier, [NSThread currentThread]);
        _setButtonData(button, updateOptions, buttonWidth, buttonFlags, nstext, img, jaction);
    } else {
        nstrace(@"async update button [%@] (thread: %@)", container.identifier, [NSThread currentThread]);
        dispatch_async(dispatch_get_main_queue(), ^{
            // NOTE: block is copied, img/text objects is automatically retained
            // nstrace(@"\tperform update button [%@] (thread: %@)", container.identifier, [NSThread currentThread]);
            _setButtonData(button, updateOptions, buttonWidth, buttonFlags, nstext, img, jaction);
        });
    }

    [edtPool release];
}
