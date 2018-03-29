#import "ButtonDesc.h"
#import "Utils.h"

@interface ButtonDesc() {
    execute _action;
}
@end

@interface ButtonImgDesc()
@property (retain, nonatomic) NSImage * img;
@end

@interface ButtonTextDesc()
@property (retain, nonatomic) NSString * text;
@end

@interface ButtonImgTextDesc()
@property (retain, nonatomic) NSImage * img;
@property (retain, nonatomic) NSString * text;
@end

@implementation ButtonDesc
- (id)init:(execute)act {
    self = [super init];
    if (self)
        _action = act;
    return self;
}
- (void)doAction{
    nstrace(@"doAction [%@]", self.uid);
    (*_action)();
}
@end

@implementation ButtonImgDesc
- (id)init:(NSImage *)img act:(execute)act {
    self = [super init:act];
    if (self)
        self.img = img;
    return self;
}
- (nullable NSTouchBarItem *)create {
    nstrace(@"create button.img [%@]", self.uid);
    NSButton * theButton = [NSButton buttonWithImage:self.img target:self action:@selector(doAction)];// is autorelease
    NSCustomTouchBarItem *customItemForButton = [[[NSCustomTouchBarItem alloc] initWithIdentifier:self.uid] autorelease];
    customItemForButton.view = theButton; // NOTE: view is strong
    return customItemForButton;
}
@synthesize img;
@end

@implementation ButtonTextDesc
- (id)init:(NSString*)text act:(execute)act {
    self = [super init:act];
    if (self)
        self.text = text;
    return self;
}
- (nullable __kindof NSTouchBarItem *)create {
    nstrace(@"create button.text [%@]", self.uid);
    NSButton * theButton = [NSButton buttonWithTitle:self.text target:self action:@selector(doAction)]; // is autorelease
    NSCustomTouchBarItem *customItemForButton = [[[NSCustomTouchBarItem alloc] initWithIdentifier:self.uid] autorelease];
    customItemForButton.view = theButton; // NOTE: view is strong
    return customItemForButton;
}
@synthesize text;
@end


@implementation ButtonImgTextDesc
- (id)init:(NSImage *)img text:(NSString*)text act:(execute)act {
    self = [super init:act];
    if (self) {
        self.text = text;
        self.img = img;
    }
    return self;
}
- (nullable __kindof NSTouchBarItem *)create {
    nstrace(@"create button.imgAndText [%@]", self.uid);

    NSButton *button = [[[NSButton alloc] init] autorelease];
    [button setImagePosition:NSImageLeft];
    [button setImage:self.img];
    [button setTitle:self.text];
    [button setTarget:self];
    [button setAction:@selector(doAction)];
    [button setBezelStyle:NSRoundedBezelStyle];

    NSCustomTouchBarItem *customItemForButton = [[[NSCustomTouchBarItem alloc] initWithIdentifier:self.uid] autorelease];
    customItemForButton.view = button; // NOTE: view is strong
    return customItemForButton;
}
@synthesize img, text;
@end

